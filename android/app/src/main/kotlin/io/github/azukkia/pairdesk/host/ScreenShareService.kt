package io.github.azukkia.pairdesk.host

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.appGraph
import io.github.azukkia.pairdesk.session.Notifications
import io.github.azukkia.pairdesk.session.SessionActionReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service of type `mediaProjection`, running while a computer
 * views (and maybe controls) this phone. Android 10+ only lets an app capture
 * its screen from such a service, started after the user allowed the capture
 * and before the capture starts ([HostSession] waits for [running]). Its
 * notification says who is connected, with an End button.
 */
class ScreenShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = appGraph.hosts.current.value?.takeIf { it.isLive }
        val notification = notification(this, session?.peer?.label, session?.controlled == true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifications.ID_HOST, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(Notifications.ID_HOST, notification)
            }
        } catch (e: RuntimeException) {
            appGraph.log.warn("[host] foreground service refused: ${e.message}")
            _running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _running.value = false
        } else {
            _running.value = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)

        /** True once the service is in the foreground (the capture may start). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }

        /** Shows who is connected now ([controlled]: the partner may control the phone). */
        fun update(context: Context, name: String?, controlled: Boolean) {
            if (!_running.value || !Notifications.allowed(context)) return
            try {
                NotificationManagerCompat.from(context).notify(Notifications.ID_HOST, notification(context, name, controlled))
            } catch (_: SecurityException) {
                // Notifications not allowed.
            }
        }

        fun notification(context: Context, name: String?, controlled: Boolean): Notification {
            Notifications.ensureChannels(context)
            val text = when {
                name == null -> context.getString(R.string.notify_session_ending)
                controlled -> context.getString(R.string.notify_host_controlled, name)
                else -> context.getString(R.string.notify_host_viewed, name)
            }
            return NotificationCompat.Builder(context, Notifications.CHANNEL_SESSIONS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.notify_host_title))
                .setContentText(text)
                .setContentIntent(Notifications.openApp(context, 40))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    R.drawable.ic_call_end,
                    context.getString(R.string.session_end),
                    Notifications.action(context, SessionActionReceiver.ACTION_END_HOST, null, 41),
                )
                .build()
        }
    }
}
