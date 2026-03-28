package com.apidebug.inspector.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.apidebug.inspector.InspectorApplication
import com.apidebug.inspector.MainActivity
import com.apidebug.inspector.R
import com.apidebug.inspector.proxy.LocalProxyState

class VpnCaptureService : VpnService() {
    private var tunnelInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startCapture() {
        if (tunnelInterface != null) return

        startForeground(NOTIFICATION_ID, buildNotification())
        val container = (application as InspectorApplication).container
        val routingMode = container.settingsRepository.settings.value.vpnRoutingMode
        val builder = Builder()
            .setSession("API Debug Inspector")
            .setMtu(DEFAULT_MTU)
            .addAddress(VIRTUAL_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (routingMode == VpnRoutingMode.HTTP_PROXY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy("127.0.0.1", LocalProxyState.DEFAULT_PROXY_PORT)
            )
        }

        tunnelInterface = builder.establish()
        container.vpnSocketProtector.install { socket -> protect(socket) }
        container.vpnCaptureController.attachSocketProtector { socketFd -> protect(socketFd) }
        tunnelInterface?.let { established ->
            container.vpnCaptureController.bindTunnel(
                tunnelInterface = established,
                mtu = DEFAULT_MTU,
                virtualAddress = "$VIRTUAL_ADDRESS/32",
                requestedRoutingMode = routingMode
            )
        } ?: run {
            container.vpnCaptureController.markPrepared(false)
            stopSelf()
        }
    }

    private fun stopCapture() {
        tunnelInterface?.close()
        tunnelInterface = null
        (application as InspectorApplication).container.vpnSocketProtector.install(null)
        (application as InspectorApplication).container.vpnCaptureController.detachSocketProtector()
        (application as InspectorApplication).container.vpnCaptureController.onTunnelStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.vpn_channel_name))
            .setContentText(getString(R.string.vpn_channel_description))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.apidebug.inspector.capture.START"
        const val ACTION_STOP = "com.apidebug.inspector.capture.STOP"

        private const val CHANNEL_ID = "vpn_capture_sessions"
        private const val NOTIFICATION_ID = 73
        private const val DEFAULT_MTU = 1500
        private const val VIRTUAL_ADDRESS = "10.10.0.2"
    }
}
