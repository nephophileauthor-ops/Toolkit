package com.apidebug.inspector.capture

import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.apidebug.inspector.data.SettingsRepository
import com.apidebug.inspector.nativebridge.Tun2SocksBridge
import com.apidebug.inspector.nativebridge.Tun2SocksConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VpnCaptureController(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val proxyPort: Int,
    private val tun2SocksBridge: Tun2SocksBridge
) {
    private val packetRouter = TunnelPacketRouter(proxyPort)
    private val tunSessionManager = TunSessionManager(
        packetRouter = packetRouter,
        onPacketObserved = ::onPacketObserved,
        onError = ::onError
    )

    private val _state = MutableStateFlow(
        VpnCaptureState(
            prepared = VpnService.prepare(appContext) == null,
            requestedRoutingMode = settingsRepository.settings.value.vpnRoutingMode,
            effectiveRoutingMode = settingsRepository.settings.value.vpnRoutingMode,
            nativeBridgeAvailable = tun2SocksBridge.isRealBackendAvailable(),
            nativeBridgeStubFallback = tun2SocksBridge.isStubFallbackLoaded()
        )
    )
    val state: StateFlow<VpnCaptureState> = _state.asStateFlow()

    fun vpnPermissionIntent(): Intent? = VpnService.prepare(appContext)

    fun attachSocketProtector(protector: (Int) -> Boolean) {
        tun2SocksBridge.updateSocketProtector(protector)
    }

    fun detachSocketProtector() {
        tun2SocksBridge.updateSocketProtector { false }
    }

    fun markPrepared(prepared: Boolean) {
        _state.value = _state.value.copy(
            prepared = prepared,
            nativeBridgeAvailable = tun2SocksBridge.isRealBackendAvailable(),
            nativeBridgeStubFallback = tun2SocksBridge.isStubFallbackLoaded()
        )
    }

    fun setRoutingMode(mode: VpnRoutingMode) {
        settingsRepository.setVpnRoutingMode(mode)
        _state.value = _state.value.copy(
            requestedRoutingMode = mode,
            effectiveRoutingMode = if (_state.value.active) _state.value.effectiveRoutingMode else mode,
            nativeBridgeAvailable = tun2SocksBridge.isRealBackendAvailable(),
            nativeBridgeStubFallback = tun2SocksBridge.isStubFallbackLoaded()
        )
    }

    fun startService() {
        appContext.startService(Intent(appContext, VpnCaptureService::class.java).apply {
            action = VpnCaptureService.ACTION_START
        })
    }

    fun stopService() {
        appContext.startService(Intent(appContext, VpnCaptureService::class.java).apply {
            action = VpnCaptureService.ACTION_STOP
        })
    }

    fun bindTunnel(
        tunnelInterface: ParcelFileDescriptor,
        mtu: Int,
        virtualAddress: String,
        requestedRoutingMode: VpnRoutingMode
    ) {
        tunSessionManager.stop()
        tun2SocksBridge.stop()

        val nativeAvailable = tun2SocksBridge.isRealBackendAvailable()
        val nativeStubFallback = tun2SocksBridge.isStubFallbackLoaded()
        var effectiveRoutingMode = requestedRoutingMode
        var lastRouteDecision = ""
        var lastError: String? = null

        when (requestedRoutingMode) {
            VpnRoutingMode.OBSERVE_ONLY -> {
                tunSessionManager.start(tunnelInterface)
                lastRouteDecision = "OBSERVE: Kotlin TUN reader active"
            }

            VpnRoutingMode.HTTP_PROXY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lastRouteDecision = "ROUTE_TO_PROXY: VPN HTTP proxy points traffic to 127.0.0.1:$proxyPort"
                } else {
                    effectiveRoutingMode = VpnRoutingMode.OBSERVE_ONLY
                    tunSessionManager.start(tunnelInterface)
                    lastRouteDecision = "OBSERVE: HTTP proxy mode unsupported on this Android level"
                    lastError = "VPN HTTP proxy mode needs Android 10+; running observe-only reader instead"
                }
            }

            VpnRoutingMode.NATIVE_TUN2SOCKS -> {
                if (!nativeAvailable) {
                    effectiveRoutingMode = VpnRoutingMode.OBSERVE_ONLY
                    tunSessionManager.start(tunnelInterface)
                    lastRouteDecision = if (nativeStubFallback) {
                        "OBSERVE: JNI wrapper loaded with stub fallback"
                    } else {
                        "OBSERVE: Native bridge library not loaded"
                    }
                    lastError = if (nativeStubFallback) {
                        "JNI wrapper is present but real libtunbridge.so is missing; drop ABI binaries into jniLibs to enable transparent routing"
                    } else {
                        "Native tun2socks bridge unavailable; add libtunbridge.so to jniLibs to enable transparent routing"
                    }
                } else {
                    val started = tun2SocksBridge.start(
                        tunInterface = tunnelInterface,
                        config = Tun2SocksConfig(
                            mtu = mtu,
                            socks5Address = "",
                            httpProxyAddress = "127.0.0.1:$proxyPort",
                            udpEnabled = false,
                            dnsResolver = "1.1.1.1:53"
                        )
                    )
                    if (started) {
                        lastRouteDecision = "ROUTE_TO_PROXY: Native tun2socks bridge forwarding streams to 127.0.0.1:$proxyPort"
                    } else {
                        effectiveRoutingMode = VpnRoutingMode.OBSERVE_ONLY
                        tunSessionManager.start(tunnelInterface)
                        lastRouteDecision = "OBSERVE: Native bridge failed to start"
                        lastError = "Native tun2socks bridge failed during startup; running observe-only reader instead"
                    }
                }
            }
        }

        _state.value = _state.value.copy(
            active = true,
            prepared = true,
            mtu = mtu,
            virtualAddress = virtualAddress,
            requestedRoutingMode = requestedRoutingMode,
            effectiveRoutingMode = effectiveRoutingMode,
            nativeBridgeAvailable = nativeAvailable,
            nativeBridgeStubFallback = nativeStubFallback,
            lastRouteDecision = lastRouteDecision,
            lastError = lastError
        )
    }

    fun onTunnelStopped() {
        tunSessionManager.stop()
        tun2SocksBridge.stop()
        _state.value = _state.value.copy(
            active = false,
            effectiveRoutingMode = _state.value.requestedRoutingMode,
            nativeBridgeAvailable = tun2SocksBridge.isRealBackendAvailable(),
            nativeBridgeStubFallback = tun2SocksBridge.isStubFallbackLoaded()
        )
    }

    fun close() {
        tun2SocksBridge.stop()
        tunSessionManager.close()
    }

    private fun onPacketObserved(packet: TunnelPacket, decision: TunnelRouteDecision) {
        _state.value = _state.value.copy(
            observedPackets = _state.value.observedPackets + 1,
            lastPacketSummary = "${packet.protocol} ${packet.sourceAddress}:${packet.sourcePort ?: 0} -> ${packet.destinationAddress}:${packet.destinationPort ?: 0}",
            lastRouteDecision = "${decision.action}: ${decision.reason}",
            lastError = null
        )
    }

    private fun onError(message: String) {
        _state.value = _state.value.copy(lastError = message)
    }
}
