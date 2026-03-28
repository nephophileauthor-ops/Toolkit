package com.apidebug.inspector.capture

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunSessionManager(
    private val packetRouter: TunnelPacketRouter,
    private val onPacketObserved: (TunnelPacket, TunnelRouteDecision) -> Unit,
    private val onError: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    fun start(tunnelInterface: ParcelFileDescriptor) {
        stop()

        readJob = scope.launch {
            val inputStream = FileInputStream(tunnelInterface.fileDescriptor)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            runCatching {
                while (isActive) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) continue
                    ensureActive()
                    val packet = PacketParser.parse(buffer, read) ?: continue
                    val routeDecision = packetRouter.route(packet)
                    onPacketObserved(packet, routeDecision)
                }
            }.onFailure { error ->
                onError(error.message ?: "TUN reader failed")
            }
        }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
