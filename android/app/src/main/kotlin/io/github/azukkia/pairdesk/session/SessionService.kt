package io.github.azukkia.pairdesk.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.azukkia.pairdesk.MainActivity
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.appGraph
import io.github.azukkia.pairdesk.core.transport.Logger

/**
 * Foreground service kept while the phone controls a computer, so that the
 * session survives a short trip to another app (copying a code, answering a
 * message): without it Android freezes or kills the cached process within
 * seconds and the computer sees the connection drop.
 *
 * Type `specialUse` (Android 14+): none of the predefined types fits a
 * user-initiated remote desktop session — `dataSync` is for transfers (and is
 * time-limited on Android 15), `connectedDevice` for external hardware
 * (Bluetooth, USB…, with matching permissions), `mediaPlayback` for media
 * continuing in the background (the picture is not shown there) and
 * `mediaProjection` for sharing this phone's own screen. The use is described
 * by the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property of the manifest (and in
 * the Play Console declaration). The service is only started from the visible
 * app (when a session opens), never from the background; it stops with the
 * last session, from its notification's Disconnect action, or when the user
 * swipes the app away.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val viewers = appGraph.viewers
        if (intent?.action == ACTION_DISCONNECT) viewers.disconnectAll()
        val names = viewers.live.value.map { it.peer.label }
        // Always in the foreground first: a service started with
        // startForegroundService() must call startForeground() even to stop.
        startInForeground(notification(this, names))
        if (names.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped PairDesk away: the sessions end with it.
        appGraph.viewers.disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "io.github.azukkia.pairdesk.action.DISCONNECT"
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_sessions), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notify_channel_sessions_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun notification(context: Context, names: List<String>): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val disconnect = PendingIntent.getService(
                context,
                1,
                Intent(context, SessionService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = when (names.size) {
                0 -> context.getString(R.string.notify_session_ending)
                1 -> context.getString(R.string.notify_session_one, names[0])
                else -> context.getString(R.string.notify_session_many, names.joinToString(", "))
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notify_session_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_power, context.getString(R.string.viewer_disconnect), disconnect)
                .build()
        }
    }
}

/** Starts, updates and stops [SessionService] for the running sessions. Main thread. */
class SessionForeground(private val context: Context, private val log: Logger) {
    private var running = false
    private var shown: List<String> = emptyList()

    fun update(names: List<String>) {
        if (names.isEmpty()) {
            if (running) {
                running = false
                shown = emptyList()
                context.stopService(Intent(context, SessionService::class.java))
            }
            return
        }
        if (running && names == shown) return
        val intent = Intent(context, SessionService::class.java)
        try {
            if (running) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
            running = true
            shown = names
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (app not visible) or
            // IllegalStateException: the session still runs while the app is visible.
            log.warn("[session] foreground service not started: ${e.message}")
        }
    }
}
