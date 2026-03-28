package com.apidebug.inspector.capture

class TunnelPacketRouter(
    private val proxyPort: Int
) {
    fun route(packet: TunnelPacket): TunnelRouteDecision {
        return when {
            packet.protocol == "TCP" && packet.destinationPort in setOf(80, 443) -> {
                TunnelRouteDecision(
                    action = TunnelRouteAction.ROUTE_TO_PROXY,
                    reason = "HTTP(S) candidate detected for local proxy port $proxyPort"
                )
            }

            packet.protocol == "UDP" && packet.destinationPort == 53 -> {
                TunnelRouteDecision(
                    action = TunnelRouteAction.BYPASS,
                    reason = "DNS traffic should stay outside the explicit proxy path"
                )
            }

            else -> {
                TunnelRouteDecision(
                    action = TunnelRouteAction.OBSERVE,
                    reason = "Packet observed only; full TCP/UDP forwarding needs tun2socks/netstack"
                )
            }
        }
    }
}
