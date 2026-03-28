# Tunbridge

This shared library reconstructs TCP/UDP flows from the Android VPN TUN file
descriptor using `golang.getoutline.org/sdk/network/lwip2transport`.

## Exported C symbols

- `tunbridge_start`
- `tunbridge_stop`
- `tunbridge_set_protect_callback`

These are the symbols consumed by
`app/src/main/cpp/tun2socks_bridge.cpp`.

## Routing behavior

- If `socks5Address` is set, TCP is routed through SOCKS5 and UDP is enabled only
  when `udpEnabled=true`.
- If `httpProxyAddress` is set, TCP is routed to the local HTTP proxy.
  - Ports `80`, `8000`, `8080`, and `8888` are treated as plaintext HTTP and sent
    directly to the proxy socket.
  - Other TCP ports are wrapped in an HTTP `CONNECT` tunnel.
  - UDP is intentionally dropped because HTTP CONNECT does not provide a datagram
    transport.

## Build

PowerShell:

```powershell
cd app\src\main\go\tunbridge
.\build-android.ps1
.\sync-native-bridge.ps1
```

Linux / GitHub Actions:

```bash
cd app/src/main/go/tunbridge
chmod +x build-android.sh sync-native-bridge.sh
ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/25.2.9519653" ./build-android.sh
./sync-native-bridge.sh
```

Manual commands:

```powershell
$env:ANDROID_NDK_HOME="C:\Android\Sdk\ndk\27.0.12077973"
$toolchain="$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin"

$env:CGO_ENABLED="1"
$env:GOOS="android"
$env:GOARCH="arm64"
$env:CC="$toolchain\aarch64-linux-android24-clang"
go build -trimpath -buildmode=c-shared -ldflags="-s -w" -o build\android\arm64-v8a\libtunbridge.so .

$env:GOARCH="amd64"
$env:GOAMD64="v1"
$env:CC="$toolchain\x86_64-linux-android24-clang"
go build -trimpath -buildmode=c-shared -ldflags="-s -w" -o build\android\x86_64\libtunbridge.so .
```

The generated header file will be emitted next to each `.so`.
Use `.\sync-native-bridge.ps1` to copy the latest `.so` files into
`app/src/main/jniLibs/<abi>/` and refresh `app/src/main/cpp/include/tunbridge.h`.
On Linux/GitHub Actions, use `./sync-native-bridge.sh` for the same copy flow.

## Important runtime note

The protect callback covers sockets created by the Go bridge itself.
If your local Kotlin proxy opens upstream sockets with OkHttp, those sockets still
need `VpnService.protect(...)` on the Android side, otherwise they can loop back
into the VPN path.
