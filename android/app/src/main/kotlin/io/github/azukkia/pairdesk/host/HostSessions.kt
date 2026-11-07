package io.github.azukkia.pairdesk.host

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.MainThread
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.net.IncomingState
import io.github.azukkia.pairdesk.session.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Computers connecting to this phone (it is the host): the request
 * notification while PairDesk is not on screen, the screen capture consent,
 * and the [HostSession] once accepted (kept until its screen closes). One at
 * a time, like the desktop. Created on the main thread when the app starts.
 */
class HostSessions(private val graph: AppGraph) {
    private val context: Context = graph.appContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _current = MutableStateFlow<HostSession?>(null)

    /** The session accepted last (ended ones until their screen closes). */
    val current: StateFlow<HostSession?> = _current.asStateFlow()

    private val _acceptRequest = MutableStateFlow<String?>(null)

    /** A request accepted from its notification: the app asks for the screen capture consent. */
    val acceptRequest: StateFlow<String?> = _acceptRequest.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.network.incoming.distinctUntilChangedBy { it?.sid to it?.state }.collect { request ->
                if (request != null && request.state == IncomingState.PENDING) {
                    // On screen, the request dialog asks; otherwise a heads-up notification.
                    if (!HostSession.appVisible()) Notifications.showRequest(context, request)
                } else {
                    Notifications.cancelRequest(context)
                    if (request == null) _acceptRequest.value = null
                }
            }
        }
    }

    /** The Accept button of the request notification (MainActivity). */
    @MainThread
    fun requestAccept(sid: String) {
        Notifications.cancelRequest(context)
        if (graph.network.incoming.value?.let { it.sid == sid && it.state == IncomingState.PENDING } == true) _acceptRequest.value = sid
    }

    @MainThread
    fun consumeAcceptRequest() {
        _acceptRequest.value = null
    }

    /**
     * The user accepted request [sid] and allowed the screen capture
     * ([projection]: the consent result): starts the session. False when the
     * request is gone.
     */
    @MainThread
    fun begin(sid: String, projection: Intent): Boolean {
        val request = graph.network.incoming.value?.takeIf { it.sid == sid && it.state == IncomingState.PENDING } ?: return false
        _current.value?.let { previous -> if (previous.sid != sid) previous.dispose() }
        val session = HostSession(graph, request.session, projection, onChanged = ::refresh)
        _current.value = session
        session.start()
        return true
    }

    /** The End button of the notification. */
    @MainThread
    fun endCurrent() {
        _current.value?.takeIf { it.isLive }?.stop()
    }

    /** The session screen closed: ends (if needed) and forgets session [sid]. */
    @MainThread
    fun close(sid: String) {
        val session = _current.value?.takeIf { it.sid == sid } ?: return
        _current.value = null
        session.dispose()
    }

    /** The session started or ended (screens follow its [HostSession.core] state). */
    private fun refresh() {
        val s = _current.value ?: return
        if (s.isLive) ScreenShareService.update(context, s.peer.label, s.controlled)
    }

    companion object {
        /** How long a request accepted on the phone waits for the system's capture dialog. */
        const val CONSENT_HOLD_MS = 60_000L

        /** The system dialog asking to share the screen (the whole screen on Android 14+). */
        fun captureIntent(context: Context): Intent {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
        }
    }
}
