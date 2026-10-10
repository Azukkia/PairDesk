package io.github.azukkia.pairdesk.ui

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.net.ConnectRequest
import io.github.azukkia.pairdesk.net.ConnectResult
import io.github.azukkia.pairdesk.net.ConnectStep
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.net.SessionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the "connect to a computer" dialog. */
sealed interface ConnectUiState {
    data object Idle : ConnectUiState

    /** [step] is null until the first progress report. */
    data class Running(val peerId: String, val kind: SessionKind, val step: ConnectStep?) : ConnectUiState

    data class Failed(val peerId: String, val kind: SessionKind, val code: String, val retryIn: Long?) : ConnectUiState {
        val message: UiText get() = ConnectErrors.message(code, retryIn)

        /** Shown next to the password field instead of in the dialog. */
        val isPasswordError: Boolean get() = ConnectErrors.isPasswordError(code)

        val canRetry: Boolean get() = ConnectErrors.isRetryable(code)
    }

    /** The session is established: the UI opens its screen, then calls [ConnectFlow.consume]. */
    data class Connected(val session: OutgoingSession) : ConnectUiState
}

/**
 * The outgoing connection attempt behind the home screen (the desktop's
 * connect dialog): one attempt at a time, cancellable, retryable.
 * [connect] is [io.github.azukkia.pairdesk.net.PairDeskNetwork.connect].
 */
class ConnectFlow(
    private val scope: CoroutineScope,
    private val connect: suspend (ConnectRequest, (ConnectStep) -> Unit) -> ConnectResult,
    /** Ends a session that was established after the user cancelled. */
    private val abandon: (OutgoingSession) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastRequest: ConnectRequest? = null
    @Volatile
    private var attempt = 0

    /** Starts connecting; false while another attempt runs. */
    fun start(request: ConnectRequest): Boolean {
        if (_state.value is ConnectUiState.Running) return false
        lastRequest = request
        val id = ++attempt
        val peerId = Protocol.normalizeId(request.peerId)
        _state.value = ConnectUiState.Running(peerId, request.kind, null)
        job = scope.launch {
            val result = connect(request) { step ->
                // Progress is reported from the signaling thread; ignore a stale attempt.
                val current = _state.value
                if (id == attempt && current is ConnectUiState.Running) _state.value = current.copy(step = step)
            }
            if (id != attempt) {
                // Cancelled meanwhile: a late success must not stay open.
                if (result is ConnectResult.Success) abandon(result.outgoing)
                return@launch
            }
            _state.value = when (result) {
                is ConnectResult.Success -> ConnectUiState.Connected(result.outgoing)
                is ConnectResult.Failure -> ConnectUiState.Failed(peerId, request.kind, result.code, result.retryIn)
            }
        }
        return true
    }

    /** Abandons the running attempt (the dialog's Cancel button). */
    fun cancel() {
        attempt++
        job?.cancel()
        job = null
        _state.value = ConnectUiState.Idle
    }

    /** Same request again (the error's Retry button). */
    fun retry(): Boolean {
        val request = lastRequest ?: return false
        if (_state.value is ConnectUiState.Running) return false
        _state.value = ConnectUiState.Idle
        return start(request)
    }

    /** Closes the error. */
    fun dismiss() {
        if (_state.value is ConnectUiState.Failed) _state.value = ConnectUiState.Idle
    }

    /** The session screen was opened for [ConnectUiState.Connected]. */
    fun consume(): OutgoingSession? {
        val connected = _state.value as? ConnectUiState.Connected ?: return null
        _state.value = ConnectUiState.Idle
        return connected.session
    }
}
