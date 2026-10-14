package io.github.azukkia.pairdesk.session

import androidx.annotation.MainThread
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.net.SessionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [ViewerSession]s of the app (one per outgoing control session), kept
 * while their screen exists. Keeps the [SessionService] (foreground service)
 * running while at least one of them is live. Main thread only.
 */
class ViewerSessions(private val graph: AppGraph) {
    private val sessions = LinkedHashMap<String, ViewerSession>()
    private val foreground = SessionForeground(graph.appContext, graph.log)

    private val _live = MutableStateFlow<List<ViewerSession>>(emptyList())

    /** Sessions still running (not ended). */
    val live: StateFlow<List<ViewerSession>> = _live.asStateFlow()

    private val _all = MutableStateFlow<List<String>>(emptyList())

    /** Session ids of every viewer kept (ended ones until their screen closes). */
    val all: StateFlow<List<String>> = _all.asStateFlow()

    fun get(sid: String): ViewerSession? = sessions[sid]

    /**
     * The viewer of outgoing control session [sid], created on first call; null
     * when there is no such session (it ended before its screen opened).
     */
    @MainThread
    fun obtain(sid: String): ViewerSession? {
        sessions[sid]?.let { return it }
        val outgoing = graph.network.outgoingSession(sid)?.takeIf { it.kind == SessionKind.CONTROL } ?: return null
        val session = ViewerSession(graph, outgoing, onLiveChanged = ::refresh)
        sessions[sid] = session
        refresh()
        return session
    }

    /** Ends (if needed) and forgets the viewer of [sid]: its screen closed. */
    @MainThread
    fun close(sid: String) {
        val session = sessions.remove(sid) ?: return
        session.dispose()
        refresh()
    }

    /** Disconnects every live session (notification action, app removed from the recent apps). */
    @MainThread
    fun disconnectAll() {
        for (s in sessions.values.toList()) if (s.isLive) s.disconnect()
        refresh()
    }

    private fun refresh() {
        val live = sessions.values.filter { it.isLive }
        _live.value = live
        _all.value = sessions.keys.toList()
        foreground.update(live.map { it.peer.label })
    }
}
