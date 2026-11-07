package io.github.azukkia.pairdesk.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.azukkia.pairdesk.MainActivity
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.appGraph
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.net.IncomingSession

/**
 * Notification channels and the notifications that are not the ongoing
 * session ones: connection requests (high priority, Accept / Decline) and
 * chat messages of a computer viewing this phone.
 */
object Notifications {
    /** Ongoing sessions (also created by [SessionService]). */
    const val CHANNEL_SESSIONS = "sessions"
    const val CHANNEL_REQUESTS = "requests"
    const val CHANNEL_CHAT = "chat"

    const val ID_CAMERA = 2
    const val ID_HOST = 3
    const val ID_REQUEST = 4
    const val ID_CHAT = 5

    /** MainActivity: accept the incoming request [EXTRA_SID] (asks for the screen sharing consent). */
    const val ACTION_ACCEPT_INCOMING = "io.github.azukkia.pairdesk.action.ACCEPT_INCOMING"
    const val EXTRA_SID = "sid"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        fun create(id: String, name: Int, desc: Int, importance: Int) {
            if (manager.getNotificationChannel(id) != null) return
            manager.createNotificationChannel(
                NotificationChannel(id, context.getString(name), importance).apply {
                    description = context.getString(desc)
                    if (importance < NotificationManager.IMPORTANCE_DEFAULT) setShowBadge(false)
                },
            )
        }
        create(CHANNEL_SESSIONS, R.string.notify_channel_sessions, R.string.notify_channel_sessions_desc, NotificationManager.IMPORTANCE_LOW)
        create(CHANNEL_REQUESTS, R.string.notify_channel_requests, R.string.notify_channel_requests_desc, NotificationManager.IMPORTANCE_HIGH)
        create(CHANNEL_CHAT, R.string.notify_channel_chat, R.string.notify_channel_chat_desc, NotificationManager.IMPORTANCE_HIGH)
    }

    /** False on Android 13+ when the user did not allow notifications. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app (its current screen). */
    fun openApp(context: Context, requestCode: Int = 0): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A notification button handled by [SessionActionReceiver]. */
    fun action(context: Context, action: String, sid: String?, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, SessionActionReceiver::class.java).setAction(action).apply { if (sid != null) putExtra(EXTRA_SID, sid) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A computer asks to connect while PairDesk is not on screen. */
    fun showRequest(context: Context, request: IncomingSession) {
        if (!allowed(context)) return
        ensureChannels(context)
        val name = request.peerName.ifBlank { context.getString(R.string.incoming_unknown_name) }
        val accept = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_ACCEPT_INCOMING)
                .putExtra(EXTRA_SID, request.sid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.incoming_title))
            .setContentText(context.getString(R.string.incoming_body, name, Protocol.formatId(request.peerId)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.incoming_body, name, Protocol.formatId(request.peerId))))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp(context, 11))
            .setAutoCancel(true)
            .setTimeoutAfter(request.deadline?.let { it - System.currentTimeMillis() }?.coerceAtLeast(1_000) ?: 45_000)
            .addAction(R.drawable.ic_power, context.getString(R.string.incoming_decline), action(context, SessionActionReceiver.ACTION_DECLINE, request.sid, 12))
            .addAction(R.drawable.ic_shield, context.getString(R.string.incoming_accept), accept)
            .build()
        post(context, ID_REQUEST, notification)
    }

    fun cancelRequest(context: Context) = NotificationManagerCompat.from(context).cancel(ID_REQUEST)

    /** A chat message of the computer viewing this phone. */
    fun showChat(context: Context, from: String, text: String) {
        if (!allowed(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(from)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context, 20))
            .setAutoCancel(true)
            .build()
        post(context, ID_CHAT, notification)
    }

    fun cancelChat(context: Context) = NotificationManagerCompat.from(context).cancel(ID_CHAT)

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed.
        }
    }
}

/** Buttons of the PairDesk notifications: decline a request, end a session. */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.appGraph
        val sid = intent.getStringExtra(Notifications.EXTRA_SID)
        when (intent.action) {
            ACTION_DECLINE -> {
                if (sid != null) graph.network.declineIncoming(sid)
                Notifications.cancelRequest(context)
            }
            ACTION_END_HOST -> graph.hosts.endCurrent()
            ACTION_END_CAMERA -> graph.cameras.endAll()
        }
    }

    companion object {
        const val ACTION_DECLINE = "io.github.azukkia.pairdesk.action.DECLINE_INCOMING"
        const val ACTION_END_HOST = "io.github.azukkia.pairdesk.action.END_HOST"
        const val ACTION_END_CAMERA = "io.github.azukkia.pairdesk.action.END_CAMERA"
    }
}
