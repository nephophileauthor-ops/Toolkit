package com.apidebug.inspector.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketParser {
    fun parse(packetBytes: ByteArray, length: Int): TunnelPacket? {
        if (length < 20) return null

        val buffer = ByteBuffer.wrap(packetBytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        val version = ((buffer.get(0).toInt() ushr 4) and 0xF)
        if (version != 4) return null

        val headerLength = (buffer.get(0).toInt() and 0x0F) * 4
        if (length < headerLength || headerLength < 20) return null

        val protocol = buffer.get(9).toInt() and 0xFF
        val sourceAddress = ipV4Address(buffer, 12)
        val destinationAddress = ipV4Address(buffer, 16)
        val payloadSize = (length - headerLength).coerceAtLeast(0)

        return when (protocol) {
            6 -> parseTransportPacket(
                packetBytes = packetBytes,
                headerLength = headerLength,
                length = length,
                protocolName = "TCP",
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                payloadSize = payloadSize
            )

            17 -> parseTransportPacket(
                packetBytes = packetBytes,
                headerLength = headerLength,
                length = length,
                protocolName = "UDP",
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                payloadSize = payloadSize
            )

            else -> TunnelPacket(
                ipVersion = version,
                protocol = "IP-$protocol",
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                payloadSize = payloadSize
            )
        }
    }

    private fun parseTransportPacket(
        packetBytes: ByteArray,
        headerLength: Int,
        length: Int,
        protocolName: String,
        sourceAddress: String,
        destinationAddress: String,
        payloadSize: Int
    ): TunnelPacket? {
        if (length < headerLength + 4) return null

        val transport = ByteBuffer.wrap(packetBytes, headerLength, length - headerLength).order(ByteOrder.BIG_ENDIAN)
        return TunnelPacket(
            ipVersion = 4,
            protocol = protocolName,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = transport.short.toInt() and 0xFFFF,
            destinationPort = transport.short.toInt() and 0xFFFF,
            payloadSize = payloadSize
        )
    }

    private fun ipV4Address(buffer: ByteBuffer, offset: Int): String {
        return listOf(
            buffer.get(offset).toInt() and 0xFF,
            buffer.get(offset + 1).toInt() and 0xFF,
            buffer.get(offset + 2).toInt() and 0xFF,
            buffer.get(offset + 3).toInt() and 0xFF
        ).joinToString(".")
    }
}
