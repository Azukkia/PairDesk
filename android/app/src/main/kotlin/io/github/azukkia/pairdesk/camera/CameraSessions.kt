package io.github.azukkia.pairdesk.camera

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.appGraph
import io.github.azukkia.pairdesk.net.SessionKind
import io.github.azukkia.pairdesk.session.Notifications
import io.github.azukkia.pairdesk.session.SessionActionReceiver
import io.github.azukkia.pairdesk.session.SessionForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [CameraSession]s of the app (one per outgoing camera session), kept
 * while their screen exists. Keeps [CameraService] (foreground service of
 * type camera) running while a camera captures. Main thread only.
 */
class CameraSessions(private val graph: AppGraph) {
    private val sessions = LinkedHashMap<String, CameraSession>()
    private val foreground = SessionForeground(graph.appContext, graph.log, CameraService::class.java)

    private val _all = MutableStateFlow<List<String>>(emptyList())

    /** Session ids of every camera session kept (ended ones until their screen closes). */
    val all: StateFlow<List<String>> = _all.asStateFlow()

    fun get(sid: String): CameraSession? = sessions[sid]

    /** The camera session [sid], created on first call; null when there is no such outgoing session. */
    @MainThread
    fun obtain(sid: String): CameraSession? {
        sessions[sid]?.let { return it }
        val outgoing = graph.network.outgoingSession(sid)?.takeIf { it.kind == SessionKind.CAMERA } ?: return null
        val session = CameraSession(graph, outgoing, onLiveChanged = ::refresh)
        sessions[sid] = session
        refresh()
        return session
    }

    /** Ends (if needed) and forgets the camera session [sid]: its screen closed. */
    @MainThread
    fun close(sid: String) {
        val session = sessions.remove(sid) ?: return
        session.dispose()
        refresh()
    }

    /** Ends every running camera session (notification button, app removed from the recent apps). */
    @MainThread
    fun endAll() {
        for (s in sessions.values.toList()) if (s.isLive) s.end()
        refresh()
    }

    /** Computers receiving the camera right now. */
    val capturingNames: List<String> get() = sessions.values.filter { it.isLive && it.capturing }.map { it.peer.label }

    private fun refresh() {
        _all.value = sessions.keys.toList()
        foreground.update(capturingNames)
    }
}

/**
 * Foreground service of type `camera`, kept while a camera session captures:
 * the camera keeps streaming when PairDesk goes to the background (Android
 * stops the camera of background apps otherwise). Started from the visible
 * app once the CAMERA permission is granted; ends with the last session, from
 * its notification's End button, or when the user swipes the app away.
 */
class CameraService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val names = appGraph.cameras.capturingNames
        // A service started with startForegroundService() must go to the foreground, even to stop.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(Notifications.ID_CAMERA, notification(this, names), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(Notifications.ID_CAMERA, notification(this, names))
            }
        } catch (e: RuntimeException) {
            // SecurityException without the CAMERA permission: the camera stops with the app.
            appGraph.log.warn("[camera] foreground service refused: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (names.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        appGraph.cameras.endAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        fun notification(context: Context, names: List<String>): Notification {
            Notifications.ensureChannels(context)
            val text = if (names.isEmpty()) {
                context.getString(R.string.notify_session_ending)
            } else {
                context.getString(R.string.notify_camera_text, names.joinToString(", "))
            }
            return NotificationCompat.Builder(context, Notifications.CHANNEL_SESSIONS)
                .setSmallIcon(R.drawable.ic_videocam)
                .setContentTitle(context.getString(R.string.notify_camera_title))
                .setContentText(text)
                .setContentIntent(Notifications.openApp(context, 30))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    R.drawable.ic_call_end,
                    context.getString(R.string.session_end),
                    Notifications.action(context, SessionActionReceiver.ACTION_END_CAMERA, null, 31),
                )
                .build()
        }
    }
}
