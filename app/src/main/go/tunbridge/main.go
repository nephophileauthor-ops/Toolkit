package main

/*
#include <stdbool.h>

typedef bool (*protect_socket_cb)(int);

static inline bool callProtectSocket(protect_socket_cb cb, int fd) {
	if (cb == NULL) {
		return false;
	}
	return cb(fd);
}
*/
import "C"

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.getoutline.org/sdk/network"
	"golang.getoutline.org/sdk/network/lwip2transport"
	"golang.getoutline.org/sdk/transport"
	"golang.getoutline.org/sdk/transport/socks5"
)

type bridgeConfig struct {
	tunFD           int
	mtu             int
	socks5Address   string
	httpProxyAddr   string
	udpEnabled      bool
	dnsResolverAddr string
}

type bridgeEngine struct {
	mu      sync.Mutex
	wg      sync.WaitGroup
	ctx     context.Context
	cancel  context.CancelFunc
	running bool

	config   bridgeConfig
	tunFile  *os.File
	ipDevice network.IPDevice
}

var (
	engine          = &bridgeEngine{}
	protectCallback C.protect_socket_cb
)

//export tunbridge_set_protect_callback
func tunbridge_set_protect_callback(cb C.protect_socket_cb) {
	protectCallback = cb
}

//export tunbridge_start
func tunbridge_start(
	tunFD C.int,
	mtu C.int,
	socks5Addr *C.char,
	httpProxyAddr *C.char,
	udpEnabled C.bool,
	dnsResolver *C.char,
) C.bool {
	cfg := bridgeConfig{
		tunFD:           int(tunFD),
		mtu:             int(mtu),
		socks5Address:   strings.TrimSpace(C.GoString(socks5Addr)),
		httpProxyAddr:   strings.TrimSpace(C.GoString(httpProxyAddr)),
		udpEnabled:      udpEnabled != C.bool(0),
		dnsResolverAddr: strings.TrimSpace(C.GoString(dnsResolver)),
	}

	if err := engine.start(cfg); err != nil {
		log.Printf("tunbridge start failed: %v", err)
		return C.bool(false)
	}
	return C.bool(true)
}

//export tunbridge_stop
func tunbridge_stop() {
	if err := engine.stop(); err != nil {
		log.Printf("tunbridge stop returned: %v", err)
	}
}

//export tunbridge_has_real_backend
func tunbridge_has_real_backend() C.bool {
	return C.bool(1)
}

func (e *bridgeEngine) start(cfg bridgeConfig) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.running {
		return nil
	}
	if cfg.tunFD < 0 {
		return errors.New("invalid tun file descriptor")
	}
	if cfg.mtu <= 0 {
		cfg.mtu = 1500
	}
	if cfg.socks5Address == "" && cfg.httpProxyAddr == "" {
		return errors.New("either socks5Address or httpProxyAddr is required")
	}

	_ = syscall.SetNonblock(cfg.tunFD, false)

	tunFile := os.NewFile(uintptr(cfg.tunFD), fmt.Sprintf("tun-fd-%d", cfg.tunFD))
	if tunFile == nil {
		return errors.New("failed to wrap tun file descriptor")
	}

	protectedDialer := net.Dialer{
		Timeout:   30 * time.Second,
		KeepAlive: 30 * time.Second,
		Control:   protectControlFn,
	}

	streamDialer, packetProxy, err := buildUpstreamRouting(cfg, protectedDialer)
	if err != nil {
		_ = tunFile.Close()
		return err
	}

	device, err := lwip2transport.ConfigureDevice(streamDialer, packetProxy)
	if err != nil {
		_ = tunFile.Close()
		return fmt.Errorf("configure lwip device: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	e.ctx = ctx
	e.cancel = cancel
	e.running = true
	e.config = cfg
	e.tunFile = tunFile
	e.ipDevice = device

	e.wg.Add(2)
	go e.pumpTunToStack()
	go e.pumpStackToTun()
	log.Printf("tunbridge running: mtu=%d socks=%q http=%q udp=%t", cfg.mtu, cfg.socks5Address, cfg.httpProxyAddr, cfg.udpEnabled)
	return nil
}

func (e *bridgeEngine) stop() error {
	e.shutdownNow()
	e.wg.Wait()
	return nil
}

func (e *bridgeEngine) shutdownNow() {
	e.mu.Lock()
	if !e.running {
		e.mu.Unlock()
		return
	}

	cancel := e.cancel
	tunFile := e.tunFile
	ipDevice := e.ipDevice
	e.running = false
	e.cancel = nil
	e.tunFile = nil
	e.ipDevice = nil
	e.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if ipDevice != nil {
		_ = ipDevice.Close()
	}
	if tunFile != nil {
		_ = tunFile.Close()
	}
}

func (e *bridgeEngine) pumpTunToStack() {
	defer e.wg.Done()

	bufSize := e.config.mtu
	if bufSize < 2048 {
		bufSize = 2048
	}
	buffer := make([]byte, bufSize)

	for {
		n, err := e.tunFile.Read(buffer)
		if err != nil {
			if e.ctx.Err() != nil || errors.Is(err, os.ErrClosed) {
				return
			}
			log.Printf("tun read failed: %v", err)
			e.shutdownNow()
			return
		}
		if n == 0 {
			continue
		}
		if _, err := e.ipDevice.Write(buffer[:n]); err != nil {
			if e.ctx.Err() != nil || errors.Is(err, network.ErrClosed) {
				return
			}
			log.Printf("stack write failed: %v", err)
			e.shutdownNow()
			return
		}
	}
}

func (e *bridgeEngine) pumpStackToTun() {
	defer e.wg.Done()

	bufSize := e.ipDevice.MTU()
	if bufSize < e.config.mtu {
		bufSize = e.config.mtu
	}
	buffer := make([]byte, bufSize)

	for {
		n, err := e.ipDevice.Read(buffer)
		if err != nil {
			if e.ctx.Err() != nil || errors.Is(err, io.EOF) || errors.Is(err, network.ErrClosed) {
				return
			}
			log.Printf("stack read failed: %v", err)
			e.shutdownNow()
			return
		}
		if n == 0 {
			continue
		}
		if _, err := e.tunFile.Write(buffer[:n]); err != nil {
			if e.ctx.Err() != nil || errors.Is(err, os.ErrClosed) {
				return
			}
			log.Printf("tun write failed: %v", err)
			e.shutdownNow()
			return
		}
	}
}

func buildUpstreamRouting(
	cfg bridgeConfig,
	protectedDialer net.Dialer,
) (transport.StreamDialer, network.PacketProxy, error) {
	switch {
	case cfg.socks5Address != "":
		return buildSocksBridge(cfg, protectedDialer)
	case cfg.httpProxyAddr != "":
		streamDialer := &httpProxyStreamDialer{
			proxyAddress: cfg.httpProxyAddr,
			dialer:       protectedDialer,
		}
		packetProxy := &disabledPacketProxy{}
		if cfg.udpEnabled {
			log.Printf("UDP requested with HTTP proxy %q but HTTP CONNECT has no datagram path; UDP will be dropped", cfg.httpProxyAddr)
		}
		return streamDialer, packetProxy, nil
	default:
		return nil, nil, errors.New("no upstream proxy configured")
	}
}

func buildSocksBridge(
	cfg bridgeConfig,
	protectedDialer net.Dialer,
) (transport.StreamDialer, network.PacketProxy, error) {
	endpoint := &staticStreamEndpoint{
		address: cfg.socks5Address,
		dialer:  protectedDialer,
	}

	client, err := socks5.NewClient(endpoint)
	if err != nil {
		return nil, nil, fmt.Errorf("build socks5 client: %w", err)
	}

	var packetProxy network.PacketProxy = &disabledPacketProxy{}
	if cfg.udpEnabled {
		client.EnablePacket(&staticPacketDialer{
			address: cfg.socks5Address,
			dialer:  protectedDialer,
		})
		packetProxy, err = network.NewPacketProxyFromPacketListener(client)
		if err != nil {
			return nil, nil, fmt.Errorf("create socks packet proxy: %w", err)
		}
	}

	return client, packetProxy, nil
}

func protectControlFn(_, _ string, rawConn syscall.RawConn) error {
	var protectErr error

	if rawConn == nil {
		return errors.New("nil raw connection")
	}

	controlErr := rawConn.Control(func(fd uintptr) {
		if protectCallback == nil {
			return
		}
		if ok := C.callProtectSocket(protectCallback, C.int(fd)); ok == C.bool(0) {
			protectErr = fmt.Errorf("VpnService.protect failed for fd %d", fd)
		}
	})
	if controlErr != nil {
		return controlErr
	}
	return protectErr
}

type staticStreamEndpoint struct {
	address string
	dialer  net.Dialer
}

func (e *staticStreamEndpoint) ConnectStream(ctx context.Context) (transport.StreamConn, error) {
	conn, err := e.dialer.DialContext(ctx, "tcp", e.address)
	if err != nil {
		return nil, err
	}
	return &streamConnAdapter{Conn: conn}, nil
}

type staticPacketDialer struct {
	address string
	dialer  net.Dialer
}

func (d *staticPacketDialer) DialPacket(ctx context.Context, addr string) (net.Conn, error) {
	target := d.address
	if target == "" {
		target = addr
	}
	return d.dialer.DialContext(ctx, "udp", target)
}

type httpProxyStreamDialer struct {
	proxyAddress string
	dialer       net.Dialer
}

func (d *httpProxyStreamDialer) DialStream(ctx context.Context, dstAddr string) (transport.StreamConn, error) {
	conn, err := d.dialer.DialContext(ctx, "tcp", d.proxyAddress)
	if err != nil {
		return nil, fmt.Errorf("dial local proxy %s: %w", d.proxyAddress, err)
	}

	stream := &streamConnAdapter{Conn: conn}
	if !shouldTunnelThroughHTTPConnect(dstAddr) {
		return stream, nil
	}

	if err := writeConnectRequest(conn, dstAddr); err != nil {
		_ = conn.Close()
		return nil, err
	}

	reader := bufio.NewReader(conn)
	if err := readConnectResponse(reader); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if reader.Buffered() == 0 {
		return stream, nil
	}
	return &bufferedStreamConn{
		StreamConn: stream,
		reader:     reader,
		writer:     conn,
	}, nil
}

func shouldTunnelThroughHTTPConnect(dstAddr string) bool {
	_, port, err := net.SplitHostPort(dstAddr)
	if err != nil {
		return true
	}
	switch port {
	case "80", "8000", "8080", "8888":
		return false
	default:
		return true
	}
}

func writeConnectRequest(conn net.Conn, dstAddr string) error {
	request := fmt.Sprintf(
		"CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Connection: Keep-Alive\r\nUser-Agent: ApiDebugInspector-TunBridge/1.0\r\n\r\n",
		dstAddr,
		dstAddr,
	)
	_, err := io.WriteString(conn, request)
	return err
}

func readConnectResponse(reader *bufio.Reader) error {
	statusLine, err := reader.ReadString('\n')
	if err != nil {
		return fmt.Errorf("read proxy status line: %w", err)
	}
	statusLine = strings.TrimSpace(statusLine)
	if !strings.Contains(statusLine, " 200 ") && !strings.HasSuffix(statusLine, " 200") {
		return fmt.Errorf("proxy connect failed: %s", statusLine)
	}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return fmt.Errorf("read proxy headers: %w", err)
		}
		if line == "\r\n" {
			return nil
		}
	}
}

type streamConnAdapter struct {
	net.Conn
}

func (c *streamConnAdapter) CloseRead() error {
	if halfCloser, ok := c.Conn.(interface{ CloseRead() error }); ok {
		return halfCloser.CloseRead()
	}
	return nil
}

func (c *streamConnAdapter) CloseWrite() error {
	if halfCloser, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return halfCloser.CloseWrite()
	}
	return nil
}

type bufferedStreamConn struct {
	transport.StreamConn
	reader io.Reader
	writer io.Writer
}

func (c *bufferedStreamConn) Read(p []byte) (int, error) {
	return c.reader.Read(p)
}

func (c *bufferedStreamConn) Write(p []byte) (int, error) {
	return c.writer.Write(p)
}

type disabledPacketProxy struct{}

func (p *disabledPacketProxy) NewSession(receiver network.PacketResponseReceiver) (network.PacketRequestSender, error) {
	return &disabledPacketSender{receiver: receiver}, nil
}

type disabledPacketSender struct {
	mu       sync.Mutex
	closed   bool
	receiver network.PacketResponseReceiver
}

func (s *disabledPacketSender) WriteTo(p []byte, _ netip.AddrPort) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return 0, network.ErrClosed
	}
	if len(p) == 0 {
		return 0, nil
	}
	return 0, network.ErrPortUnreachable
}

func (s *disabledPacketSender) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return nil
	}
	s.closed = true
	if s.receiver != nil {
		_ = s.receiver.Close()
	}
	return nil
}

func main() {}
