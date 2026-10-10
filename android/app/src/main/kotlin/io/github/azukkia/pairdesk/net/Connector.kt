package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.signaling.ConnectStatus
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.data.PrsUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** What the phone does in an outgoing session. */
enum class SessionKind(val wire: String) {
    /** Control a computer (view its screen, send input). */
    CONTROL(SessionMessages.KIND_CONTROL),

    /** Stream the phone's camera to a computer, which exposes it as a webcam. */
    CAMERA(SessionMessages.KIND_CAMERA),
}

/** Progress of an outgoing connection, for status texts. */
enum class ConnectStep {
    /** Probing the partner (is it online, same protocol version?). */
    SEARCHING,
    AUTHENTICATING,

    /** The password matched; the host decides. */
    AUTHENTICATED,

    /** The host's user must accept the request. */
    WAITING_APPROVAL,
}

data class ConnectRequest(
    /** As typed: spaces and dashes are ignored. */
    val peerId: String,
    /** Null or blank: use the remembered password of this partner. */
    val password: String?,
    val remember: Boolean,
    val kind: SessionKind,
)

/** An established outgoing session. [prs] allows reconnecting without asking again. */
class OutgoingSession(
    val session: SignalingSession,
    val kind: SessionKind,
    val prs: ByteArray,
) {
    val sid: String get() = session.sid
    val peerId: String get() = session.peerId
    val peerName: String get() = session.peerName
}

sealed interface ConnectResult {
    class Success(val outgoing: OutgoingSession) : ConnectResult

    /**
     * [code]: a [SignalingException] code (auth, offline, network, busy, locked,
     * version, disabled, rejected, declined, timeout, protocol, cancelled…) or
     * one of `invalid`, `self`, `need-password`, `camera-unsupported`, `unknown`.
     */
    data class Failure(val code: String, val retryIn: Long? = null) : ConnectResult
}

/** Access to the remembered partners (SettingsStore). */
interface RecentsAccess {
    fun savedPrs(id: String): ByteArray?

    fun touchRecent(id: String, name: String?, update: PrsUpdate)

    fun forgetRecentPassword(id: String)
}

/**
 * The outgoing connection flow of the desktop (SessionManager.probe/connect and
 * the connect dialog): validate the ID, probe the partner, derive the PRS (or
 * use the remembered one), authenticate, then update the recent partners.
 */
class Connector(
    private val myId: String,
    private val signaling: () -> Signaling,
    private val recents: RecentsAccess,
    /** `intro` fields for a session [SessionKind] (name, platform, appVersion, kind). */
    private val intro: (SessionKind) -> JsonObject,
    private val derivePrs: suspend (password: String, peerId: String) -> ByteArray,
    /** Waits (a little) for the transport to be online; false when it is not. */
    private val awaitOnline: suspend () -> Boolean = { true },
) {
    suspend fun connect(request: ConnectRequest, onStep: (ConnectStep) -> Unit = {}): ConnectResult {
        val peerId = Protocol.normalizeId(request.peerId)
        if (!Protocol.isValidId(peerId)) return ConnectResult.Failure("invalid")
        if (peerId == myId) return ConnectResult.Failure("self")
        val password = request.password?.takeIf { Protocol.normalizePassword(it).isNotEmpty() }
        var fromSaved = false
        try {
            onStep(ConnectStep.SEARCHING)
            if (!awaitOnline()) return ConnectResult.Failure("network")
            val probe = signaling().probe(peerId)
            if (probe.version != Protocol.PROTOCOL_VERSION.toLong()) return ConnectResult.Failure("version")

            val prs: ByteArray
            if (password != null) {
                onStep(ConnectStep.AUTHENTICATING) // deriving the PRS takes a moment
                prs = derivePrs(password, peerId)
            } else {
                prs = recents.savedPrs(peerId) ?: return ConnectResult.Failure("need-password")
                fromSaved = true
            }
            val session = signaling().connect(peerId, prs, intro(request.kind)) { status ->
                onStep(
                    when (status) {
                        ConnectStatus.AUTHENTICATING -> ConnectStep.AUTHENTICATING
                        ConnectStatus.AUTHENTICATED -> ConnectStep.AUTHENTICATED
                        ConnectStatus.WAITING_APPROVAL -> ConnectStep.WAITING_APPROVAL
                    },
                )
            }
            // A host older than 1.2 accepts a camera request as a control
            // session (no `kind` echo): it cannot receive the camera.
            if (request.kind == SessionKind.CAMERA && session.kind != SessionMessages.KIND_CAMERA) {
                signaling().close(session.sid, "unsupported")
                return ConnectResult.Failure("camera-unsupported")
            }
            recents.touchRecent(
                peerId,
                session.peerName,
                when {
                    fromSaved -> PrsUpdate.Keep
                    request.remember -> PrsUpdate.Set(prs)
                    else -> PrsUpdate.Clear
                },
            )
            return ConnectResult.Success(OutgoingSession(session, request.kind, prs))
        } catch (e: SignalingException) {
            if (fromSaved && e.code == "auth") recents.forgetRecentPassword(peerId)
            return ConnectResult.Failure(e.code, e.retryIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ConnectResult.Failure("unknown")
        }
    }
}
