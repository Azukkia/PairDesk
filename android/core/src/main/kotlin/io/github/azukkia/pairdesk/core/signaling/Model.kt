package io.github.azukkia.pairdesk.core.signaling

import io.github.azukkia.pairdesk.core.crypto.SecureChannel
import io.github.azukkia.pairdesk.core.json.str
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject

/**
 * Error of the signaling layer. [code] is one of the desktop codes:
 * `auth`, `offline`, `network`, `busy`, `locked`, `version`, `disabled`,
 * `rejected` (or the host's own reason, e.g. `declined`, `timeout`), `timeout`,
 * `cancelled`, `protocol`, `no-session`, `too-large`.
 */
class SignalingException(
    val code: String,
    message: String? = null,
    /** Seconds before a new attempt is accepted (`locked`). */
    val retryIn: Long? = null,
    /** Protocol version announced by the host in `denied`. */
    val remoteVersion: Long? = null,
) : Exception(message ?: code)

/** A host password, stored as its PRS. [kind] is `temp` or `perm`. */
class Credential(val kind: String, val prs: ByteArray)

/** Timeouts in milliseconds (same defaults as the desktop). */
data class Timeouts(
    val probe: Long = 8_000,
    val hello: Long = 20_000,
    val approval: Long = 120_000,
    val pending: Long = 30_000,
    val grace: Long = 2_500,
)

enum class Role(val wire: String) { HOST("host"), CONTROLLER("controller") }

/** Progress of [Signaling.connect], reported through `onStatus`. */
enum class ConnectStatus(val wire: String) {
    AUTHENTICATING("authenticating"),
    AUTHENTICATED("authenticated"),
    WAITING_APPROVAL("waiting-approval"),
}

/** Result of [Signaling.probe]: the protocol version announced by the peer. */
data class ProbeResult(val version: Long?)

/** An authenticated signaling session (encrypted channel with the peer). */
class SignalingSession internal constructor(
    val sid: String,
    val peerId: String,
    val role: Role,
    /** Host side: which password matched (`temp` or `perm`). */
    val credential: String?,
    val peerName: String,
    val peerPlatform: String,
    val peerVersion: String,
    /** Host side: the controller's decrypted `intro` message. */
    val intro: JsonObject?,
    /** Controller side: the host's decrypted `accepted` message. */
    val info: JsonObject?,
    val startedAt: Long,
    internal val channel: SecureChannel,
) {
    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)

    /**
     * The decrypted messages of this session (`signal`…, never `bye`), buffered
     * from the moment the session exists so that nothing is lost before the
     * caller starts reading (a host sends its offer right after `accepted`).
     * Single consumer. Closed when the session ends; [closeReason] tells why.
     * The same messages are also published on [Signaling.events].
     */
    val messages: ReceiveChannel<JsonObject> get() = inbox

    /** Why the session ended (`bye` reason of the peer, or the local reason); null while it is active. */
    @Volatile
    var closeReason: String? = null
        private set

    val isActive: Boolean get() = closeReason == null

    internal fun deliver(message: JsonObject) {
        inbox.trySend(message)
    }

    internal fun end(reason: String) {
        if (closeReason != null) return
        closeReason = reason
        inbox.close()
    }

    /**
     * Session kind: host side, what the controller asked for (`intro.kind`);
     * controller side, what the host echoed (`accepted.kind`, null for hosts
     * older than 1.2). `control` when absent on the host side.
     */
    val kind: String?
        get() = when (role) {
            Role.HOST -> if (intro?.str("kind") == "camera") "camera" else "control"
            Role.CONTROLLER -> info?.str("kind")
        }

    override fun toString(): String = "SignalingSession(sid=$sid, peer=$peerId, role=$role, name=$peerName)"
}

sealed interface SignalingEvent {
    /** Host: a controller authenticated; answer with accept / reject (optionally notifyWaiting first). */
    data class Incoming(val session: SignalingSession) : SignalingEvent

    /** A decrypted session message (anything but `bye`). */
    data class Message(val sid: String, val message: JsonObject) : SignalingEvent

    /** The peer ended the session with `bye`. */
    data class Closed(val sid: String, val reason: String) : SignalingEvent

    /** Host: a password attempt failed (wrong password, abort, timeout, forged confirm). */
    data class AuthFailed(val peerId: String, val failures: Int) : SignalingEvent
}
