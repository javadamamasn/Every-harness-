package com.harnesshub.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M6 foreground service: keeps the bridge tether alive while the app runs —
 * Android 12+ may otherwise kill background processes and trap-process the
 * harnesses. A pinned bridge connection is wired here in M7; today the service
 * holds the process (honest partial) and shows the tether notification.
 */
class BridgeConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        // TODO(M7): pin the BridgeTransport here and fan events to the app.
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Harness tether", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(com.harnesshub.app.R.string.service_tether_title))
                .setContentText(getString(com.harnesshub.app.R.string.service_tether_body))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(com.harnesshub.app.R.string.service_tether_title))
                .setContentText(getString(com.harnesshub.app.R.string.service_tether_body))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "harness_tether"
        private const val NOTIFICATION_ID = 0x6842

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, BridgeConnectionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeConnectionService::class.java))
        }
    }
}
