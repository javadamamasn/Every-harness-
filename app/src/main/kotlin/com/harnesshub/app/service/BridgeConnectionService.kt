package com.harnesshub.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.harnesshub.app.data.BridgeSettingsStore
import com.harnesshub.transport.BridgeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/**
 * M7b foreground service: keeps a PINNED bridge WebSocket alive (reconnect
 * with jittered backoff) so harness sessions survive Android 12+ background
 * limits; the notification reflects the tether state. Per-connection Hermes
 * gateway pinning lands with the shared connection registry (honest note).
 */
class BridgeConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: BridgeSettingsStore
    private var transport: BridgeTransport? = null

    override fun onCreate() {
        super.onCreate()
        settings = BridgeSettingsStore(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        scope.launch { pinLoop() }
        return START_STICKY
    }

    private suspend fun pinLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val prefs = settings.settings().first()
            if (!prefs.configured) {
                setState(BridgeState.WaitingForSetup)
                delay(5_000)
                continue
            }
            setState(BridgeState.Connecting)
            val t = BridgeTransport(clientFactory = { listener ->
                OkHttpClient().newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:${prefs.port}").build(),
                    listener,
                )
            })
            var active = false
            try {
                t.connect(prefs.token)
                if (t.awaitReady(8_000) is BridgeTransport.State.Ready) {
                    active = true
                    transport = t
                    setState(BridgeState.Ready)
                    backoff = 1_000L
                    while (scope.isActive &&
                        t.state.value !is BridgeTransport.State.Closed &&
                        t.state.value !is BridgeTransport.State.Failed
                    ) {
                        delay(1_000)
                    }
                    if (scope.isActive) setState(BridgeState.Reconnecting)
                } else {
                    setState(BridgeState.Reconnecting)
                }
            } catch (e: Exception) {
                setState(BridgeState.Reconnecting)
            } finally {
                runCatching { if (!active) t.close() }
                if (transport === t) transport = null
            }
            delay(backoff + Random.nextLong(0, 1_000))
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
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
        runCatching { transport?.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Tether lifecycle for the UI (Hub chip shows it at M7 follow-up). */
    sealed interface BridgeState {
        data object WaitingForSetup : BridgeState
        data object Connecting : BridgeState
        data object Ready : BridgeState
        data object Reconnecting : BridgeState
    }

    companion object {
        private const val CHANNEL_ID = "harness_tether"
        private const val NOTIFICATION_ID = 0x6842

        private val _bridgeState = MutableStateFlow<BridgeState>(BridgeState.Connecting)
        val bridgeState: StateFlow<BridgeState> = _bridgeState

        private fun setState(state: BridgeState) {
            _bridgeState.tryEmit(state)
        }

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
