package io.github.azukkia.pairdesk.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.BuildConfig
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.data.AppSettings
import io.github.azukkia.pairdesk.data.NetworkMode
import io.github.azukkia.pairdesk.data.SettingsStore
import io.github.azukkia.pairdesk.net.NetworkStatus
import io.github.azukkia.pairdesk.ui.UiText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Validation of a new permanent password (the desktop's settings page rules). */
object PermanentPasswordRules {
    /** The error to show, or null when [password] / [confirmation] can be saved. */
    fun validate(password: String, confirmation: String): UiText? = when {
        Protocol.normalizePassword(password).length < Protocol.MIN_PERMANENT_PASSWORD_LENGTH ->
            UiText(R.string.settings_permanent_too_short, listOf(Protocol.MIN_PERMANENT_PASSWORD_LENGTH))
        password != confirmation -> UiText(R.string.settings_permanent_mismatch)
        else -> null
    }
}

class SettingsViewModel(graph: AppGraph) : ViewModel() {
    private val settings = graph.settings
    private val network = graph.network

    val appSettings: StateFlow<AppSettings> = settings.settings
    val status: StateFlow<NetworkStatus> = network.status
    val defaultName: String = settings.defaultDisplayName
    val version: String = BuildConfig.VERSION_NAME

    /** The server URL being edited (applied with [applyServer]). */
    var serverUrl by mutableStateOf(settings.settings.value.serverUrl)

    var serverUrlError by mutableStateOf(false)
        private set

    /** Deriving and storing the permanent password's PRS takes a moment. */
    var savingPassword by mutableStateOf(false)
        private set

    fun setDisplayName(name: String) = settings.setDisplayName(name)

    fun setAllowIncoming(allow: Boolean) = settings.setAllowIncoming(allow)

    fun setAllowControl(allow: Boolean) = settings.setAllowControl(allow)

    fun usePublicRelays() {
        serverUrlError = false
        settings.setNetwork(NetworkMode.PUBLIC, serverUrl)
    }

    /** Switches to the private server [serverUrl]; false (and an error shown) when the URL is invalid. */
    fun applyServer(): Boolean {
        val url = serverUrl.trim()
        val ok = SettingsStore.isValidServerUrl(url) && settings.setNetwork(NetworkMode.SERVER, url)
        serverUrlError = !ok
        if (ok) serverUrl = url
        return ok
    }

    fun onServerUrlChange(value: String) {
        serverUrl = value
        serverUrlError = false
    }

    /**
     * Saves (or removes, with null) the permanent password; [done] receives
     * null on success or the error to show.
     */
    fun savePermanentPassword(password: String?, done: (UiText?) -> Unit) {
        if (savingPassword) return
        savingPassword = true
        viewModelScope.launch {
            val ok = try {
                network.setPermanentPassword(password)
            } finally {
                savingPassword = false
            }
            done(if (ok) null else UiText(R.string.settings_permanent_failed))
        }
    }
}
