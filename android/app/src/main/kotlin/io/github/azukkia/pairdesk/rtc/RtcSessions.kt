package io.github.azukkia.pairdesk.rtc

import android.content.Context
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.net.PairDeskNetwork
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** [SignalLink] over an established [SignalingSession] (the desktop's `session:signal` IPC). */
class SignalingLink(
    private val signaling: Signaling,
    private val session: SignalingSession,
    private val log: Logger = Logger.NONE,
) : SignalLink {
    override val messages: ReceiveChannel<JsonObject> get() = session.messages

    override fun send(message: JsonObject) {
        signaling.sendAsync(session.sid, message).invokeOnCompletion { e ->
            if (e != null) log.warn("[rtc] signal to ${session.peerId} failed: ${e.message}")
        }
    }
}

/**
 * The [RtcSession]s of the running sessions, one per signaling session (the
 * session's messages have a single reader). App-scoped: a session survives
 * its screen being recreated. A session is removed once closed, which happens
 * by itself when its signaling session ends.
 */
class RtcSessions(
    private val context: Context,
    private val network: PairDeskNetwork,
    private val log: Logger,
) {
    /** Created on first use (loads the native library). */
    val environment: RtcEnvironment by lazy { RtcEnvironment.get(context) }

    private val sessions = ConcurrentHashMap<String, RtcSession>()

    fun get(sid: String): RtcSession? = sessions[sid]?.takeUnless { it.isClosed }

    /**
     * Returns the RtcSession of the established signaling session [sid],
     * creating and starting it on first call ([configure] runs before
     * [RtcSession.start]: subscribe, add local tracks). Null when the session
     * is not (or no longer) active.
     */
    fun open(
        sid: String,
        role: RtcRole,
        camera: Boolean = false,
        configure: (RtcSession) -> Unit = {},
    ): RtcSession? {
        get(sid)?.let { return it }
        synchronized(this) {
            get(sid)?.let { return it }
            val session = network.signaling.session(sid)?.takeIf { it.isActive } ?: return null
            val rtc = RtcSession(
                env = environment,
                role = role,
                iceServers = network.iceServers(),
                link = SignalingLink(network.signaling, session, log),
                camera = camera,
                log = log,
                onClosed = { closed -> sessions.remove(sid, closed) },
            )
            sessions[sid] = rtc
            configure(rtc)
            rtc.start()
            return rtc
        }
    }

    /** Closes the RtcSession of [sid], if any (the signaling session is left alone). */
    fun close(sid: String) {
        sessions[sid]?.close()
    }
}
