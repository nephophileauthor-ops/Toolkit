package com.apidebug.inspector.capture

data class TunnelPacket(
    val timestamp: Long = System.currentTimeMillis(),
    val ipVersion: Int,
    val protocol: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val sourcePort: Int? = null,
    val destinationPort: Int? = null,
    val payloadSize: Int
)

enum class TunnelRouteAction {
    OBSERVE,
    ROUTE_TO_PROXY,
    BYPASS,
    DROP
}

data class TunnelRouteDecision(
    val action: TunnelRouteAction,
    val reason: String
)

enum class VpnRoutingMode {
    OBSERVE_ONLY,
    HTTP_PROXY,
    NATIVE_TUN2SOCKS
}

data class VpnCaptureState(
    val prepared: Boolean = false,
    val active: Boolean = false,
    val mtu: Int = 1500,
    val virtualAddress: String = "10.10.0.2/32",
    val observedPackets: Long = 0,
    val lastPacketSummary: String = "",
    val lastRouteDecision: String = "",
    val requestedRoutingMode: VpnRoutingMode = VpnRoutingMode.OBSERVE_ONLY,
    val effectiveRoutingMode: VpnRoutingMode = VpnRoutingMode.OBSERVE_ONLY,
    val nativeBridgeAvailable: Boolean = false,
    val nativeBridgeStubFallback: Boolean = false,
    val lastError: String? = null
)
