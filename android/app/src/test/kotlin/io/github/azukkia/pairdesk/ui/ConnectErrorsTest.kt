package io.github.azukkia.pairdesk.ui

import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.core.transport.TransportKind
import io.github.azukkia.pairdesk.core.transport.TransportState
import io.github.azukkia.pairdesk.net.ConnectStep
import io.github.azukkia.pairdesk.net.NetworkStatus
import io.github.azukkia.pairdesk.ui.components.NetworkTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectErrorsTest {
    @Test
    fun `error codes map to friendly messages`() {
        val expected = mapOf(
            "auth" to R.string.error_auth,
            "need-password" to R.string.error_need_password,
            "offline" to R.string.error_offline,
            "network" to R.string.error_network,
            "busy" to R.string.error_busy,
            "version" to R.string.error_version,
            "disabled" to R.string.error_disabled,
            "rejected" to R.string.error_rejected,
            "declined" to R.string.error_declined,
            "timeout" to R.string.error_timeout,
            "protocol" to R.string.error_protocol,
            "cancelled" to R.string.error_cancelled,
            "self" to R.string.error_self,
            "invalid" to R.string.error_invalid,
            "camera-unsupported" to R.string.error_camera_unsupported,
            "unsupported" to R.string.error_unsupported,
        )
        for ((code, res) in expected) assertEquals(UiText(res), ConnectErrors.message(code), code)
    }

    @Test
    fun `locked shows the delay`() {
        assertEquals(UiText(R.string.error_locked, listOf("42")), ConnectErrors.message("locked", 42))
        assertEquals(UiText(R.string.error_locked, listOf("?")), ConnectErrors.message("locked", null))
    }

    @Test
    fun `unknown codes are shown as is`() {
        assertEquals(UiText(R.string.error_unknown, listOf("weird")), ConnectErrors.message("weird"))
    }

    @Test
    fun `retry and password errors like the desktop dialog`() {
        for (code in listOf("offline", "network", "timeout", "busy", "locked")) assertTrue(ConnectErrors.isRetryable(code), code)
        for (code in listOf("auth", "version", "disabled", "declined", "rejected", "self")) assertFalse(ConnectErrors.isRetryable(code), code)
        assertTrue(ConnectErrors.isPasswordError("auth"))
        assertTrue(ConnectErrors.isPasswordError("need-password"))
        assertFalse(ConnectErrors.isPasswordError("offline"))
    }

    @Test
    fun `progress texts`() {
        assertEquals(UiText(R.string.connect_searching, listOf("123 456 789")), ConnectErrors.step(ConnectStep.SEARCHING, "123 456 789"))
        assertEquals(UiText(R.string.connect_authenticating), ConnectErrors.step(ConnectStep.AUTHENTICATING, ""))
        assertEquals(UiText(R.string.connect_authenticated), ConnectErrors.step(ConnectStep.AUTHENTICATED, ""))
        assertEquals(UiText(R.string.connect_waiting_approval), ConnectErrors.step(ConnectStep.WAITING_APPROVAL, ""))
    }

    @Test
    fun `network status texts`() {
        val online = NetworkStatus(TransportState.ONLINE, TransportKind.PUBLIC, relays = 3)
        assertEquals(UiText(R.string.status_online), NetworkTexts.label(online))
        assertEquals(UiText(R.string.status_via_relays, listOf(3)), NetworkTexts.detail(online))
        val server = NetworkStatus(TransportState.ONLINE, TransportKind.SERVER, serverUrl = "wss://x/ws")
        assertEquals(UiText(R.string.status_via_server, listOf("wss://x/ws")), NetworkTexts.detail(server))
        val connecting = NetworkStatus(TransportState.CONNECTING, TransportKind.PUBLIC)
        assertEquals(UiText(R.string.status_connecting), NetworkTexts.label(connecting))
        assertEquals(UiText(R.string.status_connecting_detail), NetworkTexts.detail(connecting))
        val offline = NetworkStatus(TransportState.OFFLINE, TransportKind.PUBLIC)
        assertEquals(UiText(R.string.status_offline_detail), NetworkTexts.detail(offline))
        val taken = NetworkStatus(TransportState.ERROR, TransportKind.SERVER, error = "id-taken")
        assertEquals(UiText(R.string.status_error), NetworkTexts.label(taken))
        assertEquals(UiText(R.string.status_id_taken), NetworkTexts.detail(taken))
    }
}
