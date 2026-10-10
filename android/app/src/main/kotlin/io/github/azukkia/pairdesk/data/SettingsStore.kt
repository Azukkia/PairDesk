package io.github.azukkia.pairdesk.data

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.crypto.B64u
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** How this device reaches the PairDesk network. */
enum class NetworkMode(val wire: String) {
    /** Public MQTT relays (default, zero configuration). */
    PUBLIC("public"),

    /** Self-hosted PairDesk server ([AppSettings.serverUrl]). */
    SERVER("server"),
    ;

    companion object {
        fun fromWire(value: String?): NetworkMode = entries.firstOrNull { it.wire == value } ?: PUBLIC
    }
}

/** User settings (everything but the identity and the secrets). */
data class AppSettings(
    /** Name shown to partners; empty = [SettingsStore.defaultDisplayName]. */
    val displayName: String = "",
    val networkMode: NetworkMode = NetworkMode.PUBLIC,
    val serverUrl: String = "",
    /** Other devices may connect to this phone (with its password). */
    val allowIncoming: Boolean = true,
    /** Partners may control this phone (needs the accessibility service). */
    val allowControl: Boolean = true,
    val hasPermanentPassword: Boolean = false,
)

/**
 * Preferences of the viewer (controlling a computer), remembered across
 * sessions like the desktop's `viewerQuality` / `viewerClipboard`. Values are
 * the wire names of the viewer types (InputMode, Quality, RemoteLayout).
 */
data class ViewerPrefs(
    /** `direct` or `touchpad`; null until the user picks one (default depends on the screen size). */
    val inputMode: String? = null,
    /** `speed`, `balanced` or `quality`. */
    val quality: String = "balanced",
    /** Copy to the phone what the computer copies. */
    val clipboardSync: Boolean = true,
    /** Keyboard layout of the remote computer (`qwerty`, `azerty`, `qwertz`); null = guessed from the phone's language. */
    val remoteLayout: String? = null,
    /** Keep the viewer in landscape whatever the auto-rotate setting. */
    val landscape: Boolean = false,
    /** The notification permission was asked once already. */
    val notificationsAsked: Boolean = false,
)

/**
 * Persistent identity and settings (src/main/settings.js of the desktop).
 *
 * The device ID (9 digits, first non-zero) and the device key are generated
 * on first use and never change. Passwords are only stored as PRS, encrypted
 * by [secrets]; the temporary password itself is kept (it is displayed) so
 * that it survives the process being killed in the background.
 * Thread-safe; state is published through [StateFlow]s.
 */
class SettingsStore(
    private val kv: KeyValueStore,
    private val secrets: SecretBox,
    /** Display name when the user did not choose one (the device model). */
    val defaultDisplayName: String,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    val deviceId: String
    val deviceKey: String

    private val _settings: MutableStateFlow<AppSettings>
    val settings: StateFlow<AppSettings>

    private val _recents: MutableStateFlow<List<StoredRecent>>
    private val _publicRecents: MutableStateFlow<List<RecentPartner>>

    /** Recent partners, most recent first (without secrets). */
    val recents: StateFlow<List<RecentPartner>>

    private val _tempPassword: MutableStateFlow<String>

    private val _viewer: MutableStateFlow<ViewerPrefs>

    /** Viewer preferences. */
    val viewer: StateFlow<ViewerPrefs>

    /** The temporary password shown to the user. */
    val tempPassword: StateFlow<String>

    init {
        var id = kv.getString(K_DEVICE_ID)
        var key = kv.getString(K_DEVICE_KEY)
        var password = kv.getString(K_TEMP_PASSWORD)
        val idChanged = !Protocol.isValidId(id)
        if (idChanged) id = Protocol.generateDeviceId(random)
        if (key == null || key.length < 32) key = Protocol.generateDeviceKey(random)
        if (password.isNullOrEmpty() || !isGeneratedPassword(password)) password = Protocol.generatePassword(rnd = random)
        kv.edit {
            putString(K_DEVICE_ID, id)
            putString(K_DEVICE_KEY, key)
            putString(K_TEMP_PASSWORD, password)
            // A PRS is bound to the ID: a new ID invalidates stored ones.
            if (idChanged) {
                remove(K_TEMP_PRS)
                remove(K_PERMANENT_PRS)
            }
        }
        deviceId = id!!
        deviceKey = key
        _tempPassword = MutableStateFlow(password!!)
        tempPassword = _tempPassword.asStateFlow()
        _settings = MutableStateFlow(load())
        settings = _settings.asStateFlow()
        _viewer = MutableStateFlow(loadViewer())
        viewer = _viewer.asStateFlow()
        _recents = MutableStateFlow(Recents.decode(kv.getString(K_RECENTS)))
        _publicRecents = MutableStateFlow(Recents.toPublic(_recents.value))
        recents = _publicRecents.asStateFlow()
    }

    private fun load() = AppSettings(
        displayName = kv.getString(K_DISPLAY_NAME) ?: "",
        networkMode = NetworkMode.fromWire(kv.getString(K_NETWORK_MODE)),
        serverUrl = kv.getString(K_SERVER_URL) ?: "",
        allowIncoming = kv.getBoolean(K_ALLOW_INCOMING, true),
        allowControl = kv.getBoolean(K_ALLOW_CONTROL, true),
        hasPermanentPassword = kv.getString(K_PERMANENT_PRS) != null,
    )

    private fun loadViewer() = ViewerPrefs(
        inputMode = kv.getString(K_VIEWER_INPUT),
        quality = kv.getString(K_VIEWER_QUALITY)?.takeIf { it in QUALITIES } ?: "balanced",
        clipboardSync = kv.getBoolean(K_VIEWER_CLIPBOARD, true),
        remoteLayout = kv.getString(K_REMOTE_LAYOUT),
        landscape = kv.getBoolean(K_VIEWER_LANDSCAPE, false),
        notificationsAsked = kv.getBoolean(K_NOTIFICATIONS_ASKED, false),
    )

    /** Changes the viewer preferences with [change] and stores them. */
    fun updateViewer(change: (ViewerPrefs) -> ViewerPrefs) = synchronized(lock) {
        val next = change(_viewer.value).let { p -> if (p.quality in QUALITIES) p else p.copy(quality = "balanced") }
        kv.edit {
            putString(K_VIEWER_INPUT, next.inputMode)
            putString(K_VIEWER_QUALITY, next.quality)
            putBoolean(K_VIEWER_CLIPBOARD, next.clipboardSync)
            putString(K_REMOTE_LAYOUT, next.remoteLayout)
            putBoolean(K_VIEWER_LANDSCAPE, next.landscape)
            putBoolean(K_NOTIFICATIONS_ASKED, next.notificationsAsked)
        }
        _viewer.value = next
    }

    /** The name announced to partners. */
    val displayName: String
        get() = _settings.value.displayName.trim().ifEmpty { defaultDisplayName }.take(MAX_NAME)

    // ───────────────────────────── settings ─────────────────────────────

    fun setDisplayName(name: String) = synchronized(lock) {
        val value = name.trim().take(MAX_NAME)
        kv.edit { putString(K_DISPLAY_NAME, value) }
        _settings.value = _settings.value.copy(displayName = value)
    }

    /**
     * Chooses the transport. A [NetworkMode.SERVER] needs a valid
     * `ws://` or `wss://` [serverUrl]; returns false (nothing changed) otherwise.
     */
    fun setNetwork(mode: NetworkMode, serverUrl: String): Boolean = synchronized(lock) {
        var url = serverUrl.trim()
        if (!isValidServerUrl(url)) {
            if (mode == NetworkMode.SERVER) return false
            url = "" // public relays: an invalid URL is simply not kept
        }
        kv.edit {
            putString(K_NETWORK_MODE, mode.wire)
            putString(K_SERVER_URL, url)
        }
        _settings.value = _settings.value.copy(networkMode = mode, serverUrl = url)
        true
    }

    fun setAllowIncoming(allow: Boolean) = synchronized(lock) {
        kv.edit { putBoolean(K_ALLOW_INCOMING, allow) }
        _settings.value = _settings.value.copy(allowIncoming = allow)
    }

    fun setAllowControl(allow: Boolean) = synchronized(lock) {
        kv.edit { putBoolean(K_ALLOW_CONTROL, allow) }
        _settings.value = _settings.value.copy(allowControl = allow)
    }

    // ───────────────────────────── host passwords ─────────────────────────────

    /** Draws a new temporary password (its PRS must be derived again). */
    fun regenerateTempPassword(): String = synchronized(lock) {
        val password = Protocol.generatePassword(rnd = random)
        kv.edit {
            putString(K_TEMP_PASSWORD, password)
            remove(K_TEMP_PRS)
        }
        _tempPassword.value = password
        password
    }

    /** The stored PRS of the current temporary password, if it was derived already. */
    fun tempPrs(): ByteArray? = synchronized(lock) {
        val stored = kv.getString(K_TEMP_PRS) ?: return null
        val sep = stored.indexOf('|')
        if (sep < 0 || stored.substring(0, sep) != passwordTag(_tempPassword.value)) return null
        secrets.open(stored.substring(sep + 1))
    }

    /** Stores the PRS of [password] if it is still the current temporary password. */
    fun storeTempPrs(password: String, prs: ByteArray): Boolean = synchronized(lock) {
        if (password != _tempPassword.value) return false
        val sealed = secrets.seal(prs) ?: return true // kept in memory only
        kv.edit { putString(K_TEMP_PRS, passwordTag(password) + "|" + sealed) }
        true
    }

    fun permanentPrs(): ByteArray? = kv.getString(K_PERMANENT_PRS)?.let(secrets::open)

    /** Sets (or removes, with null) the PRS of the permanent password. False when it cannot be stored safely. */
    fun setPermanentPrs(prs: ByteArray?): Boolean = synchronized(lock) {
        val sealed = if (prs == null) null else secrets.seal(prs) ?: return false
        kv.edit { putString(K_PERMANENT_PRS, sealed) }
        _settings.value = _settings.value.copy(hasPermanentPassword = sealed != null)
        true
    }

    // ───────────────────────────── recent partners ─────────────────────────────

    fun touchRecent(id: String, name: String?, update: PrsUpdate) = synchronized(lock) {
        val sealed = (update as? PrsUpdate.Set)?.let { secrets.seal(it.prs) }
        saveRecents(Recents.touch(_recents.value, id, name, update, sealed, clock()))
    }

    fun forgetRecent(id: String) = synchronized(lock) {
        saveRecents(Recents.forget(_recents.value, id))
    }

    fun forgetRecentPassword(id: String) = synchronized(lock) {
        saveRecents(Recents.forgetPassword(_recents.value, id))
    }

    /** The remembered PRS for partner [id], if any. */
    fun savedPrs(id: String): ByteArray? = _recents.value.firstOrNull { it.id == id }?.sealedPrs?.let(secrets::open)

    private fun saveRecents(list: List<StoredRecent>) {
        kv.edit { putString(K_RECENTS, Recents.encode(list)) }
        _recents.value = list
        _publicRecents.value = Recents.toPublic(list)
    }

    /** Short non-secret tag binding a stored PRS to its password (detects a stale PRS). */
    private fun passwordTag(password: String): String =
        B64u.encode(io.github.azukkia.pairdesk.core.crypto.sha256(("pd-temp|$deviceId|$password").toByteArray())).take(8)

    companion object {
        const val MAX_NAME = 64
        const val MAX_URL = 300

        private val SERVER_URL = Regex("^wss?://\\S+$", RegexOption.IGNORE_CASE)

        /** Same rule as the desktop settings: `ws://` or `wss://`, no spaces, ≤ 300 chars. */
        fun isValidServerUrl(url: String): Boolean = url.length <= MAX_URL && SERVER_URL.matches(url)

        fun isGeneratedPassword(password: String): Boolean =
            password.length == Protocol.DEFAULT_PASSWORD_LENGTH && password.all { it in Protocol.PASSWORD_ALPHABET }

        private const val K_DEVICE_ID = "device_id"
        private const val K_DEVICE_KEY = "device_key"
        private const val K_DISPLAY_NAME = "display_name"
        private const val K_NETWORK_MODE = "network_mode"
        private const val K_SERVER_URL = "server_url"
        private const val K_ALLOW_INCOMING = "allow_incoming"
        private const val K_ALLOW_CONTROL = "allow_control"
        private const val K_TEMP_PASSWORD = "temp_password"
        private const val K_TEMP_PRS = "temp_prs"
        private const val K_PERMANENT_PRS = "permanent_prs"
        private const val K_RECENTS = "recents"
        private const val K_VIEWER_INPUT = "viewer_input_mode"
        private const val K_VIEWER_QUALITY = "viewer_quality"
        private const val K_VIEWER_CLIPBOARD = "viewer_clipboard"
        private const val K_REMOTE_LAYOUT = "viewer_remote_layout"
        private const val K_VIEWER_LANDSCAPE = "viewer_landscape"
        private const val K_NOTIFICATIONS_ASKED = "notifications_asked"
        private val QUALITIES = setOf("speed", "balanced", "quality")
    }
}
