package io.github.azukkia.pairdesk.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.data.AppSettings
import io.github.azukkia.pairdesk.data.RecentPartner
import io.github.azukkia.pairdesk.net.ConnectRequest
import io.github.azukkia.pairdesk.net.NetworkStatus
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.net.SessionKind
import io.github.azukkia.pairdesk.ui.ConnectFlow
import io.github.azukkia.pairdesk.ui.ConnectUiState
import io.github.azukkia.pairdesk.ui.IdFormat
import io.github.azukkia.pairdesk.ui.UiText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The home screen: this phone's credentials, the partner form, recent partners. */
class HomeViewModel(graph: AppGraph) : ViewModel() {
    private val network = graph.network
    private val settings = graph.settings

    val myId: String = network.myId
    val password: StateFlow<String> = network.hostPassword
    val status: StateFlow<NetworkStatus> = network.status
    val appSettings: StateFlow<AppSettings> = settings.settings
    val recents: StateFlow<List<RecentPartner>> = settings.recents

    /** Partner ID as typed (digits only, see [IdFormat]). */
    var partnerId by mutableStateOf("")
        private set

    var partnerPassword by mutableStateOf("")
        private set

    var remember by mutableStateOf(false)

    /** Shown under the password field (wrong / missing password). */
    var passwordError by mutableStateOf<UiText?>(null)
        private set

    val connectFlow = ConnectFlow(
        scope = viewModelScope,
        connect = { request, onStep -> network.connect(request, onStep) },
        abandon = { session -> network.endOutgoing(session.sid, "cancelled") },
    )

    val connectState: StateFlow<ConnectUiState> = connectFlow.state

    init {
        viewModelScope.launch {
            connectFlow.state.collect { state ->
                // Password problems are shown on the field, like the desktop's password prompt.
                if (state is ConnectUiState.Failed && state.isPasswordError) {
                    passwordError = state.message
                    connectFlow.dismiss()
                }
            }
        }
    }

    fun onPartnerIdChange(value: String) {
        val digits = IdFormat.sanitize(value)
        if (digits != partnerId) passwordError = null
        partnerId = digits
    }

    fun onPartnerPasswordChange(value: String) {
        partnerPassword = value
        passwordError = null
    }

    /** True when a password is remembered for the partner being typed. */
    fun hasSavedPassword(recents: List<RecentPartner>): Boolean = recents.any { it.id == partnerId && it.hasPassword }

    val canConnect: Boolean get() = partnerId.length == IdFormat.LENGTH

    fun connect(kind: SessionKind) {
        passwordError = null
        connectFlow.start(
            ConnectRequest(
                peerId = partnerId,
                password = partnerPassword.takeIf { it.isNotBlank() },
                remember = remember,
                kind = kind,
            ),
        )
    }

    /** The session screen opens: clears the typed password. */
    fun onConnected(): OutgoingSession? = connectFlow.consume()?.also {
        partnerPassword = ""
        passwordError = null
    }

    fun pickRecent(recent: RecentPartner) {
        partnerId = recent.id
        partnerPassword = ""
        passwordError = null
    }

    fun forgetRecent(id: String) = settings.forgetRecent(id)

    fun regeneratePassword() {
        network.regeneratePassword()
    }
}
