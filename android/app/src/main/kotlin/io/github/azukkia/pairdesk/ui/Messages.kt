package io.github.azukkia.pairdesk.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.net.ConnectStep

/**
 * A localized text resolved at display time (keeps ViewModels free of
 * Context): a string resource, or a plurals resource when [quantity] is set.
 */
data class UiText(val res: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) {
    fun resolve(context: Context): String =
        if (quantity != null) {
            context.resources.getQuantityString(res, quantity, *args.toTypedArray())
        } else {
            context.getString(res, *args.toTypedArray())
        }

    companion object {
        /** A plurals resource for [quantity], formatted with [args]. */
        fun plural(@PluralsRes res: Int, quantity: Int, args: List<Any> = listOf(quantity)) = UiText(res, args, quantity)
    }
}

@Composable
fun UiText.text(): String =
    if (quantity != null) pluralStringResource(res, quantity, *args.toTypedArray()) else stringResource(res, *args.toTypedArray())

/**
 * Friendly messages for the error codes of an outgoing connection
 * (`error.*` of src/shared/i18n.js and the connect dialog of the desktop).
 */
object ConnectErrors {
    /** Errors worth a "Retry" button (same list as the desktop connect dialog). */
    private val RETRYABLE = setOf("offline", "network", "timeout", "busy", "locked")

    fun isRetryable(code: String): Boolean = code in RETRYABLE

    /** The user must (re)type the password: shown next to the password field. */
    fun isPasswordError(code: String): Boolean = code == "auth" || code == "need-password"

    fun message(code: String, retryIn: Long? = null): UiText = when (code) {
        "offline" -> UiText(R.string.error_offline)
        "network" -> UiText(R.string.error_network)
        "auth" -> UiText(R.string.error_auth)
        "need-password" -> UiText(R.string.error_need_password)
        "locked" -> UiText(R.string.error_locked, listOf(retryIn?.toString() ?: "?"))
        "busy" -> UiText(R.string.error_busy)
        "version" -> UiText(R.string.error_version)
        "disabled" -> UiText(R.string.error_disabled)
        "declined" -> UiText(R.string.error_declined)
        "timeout" -> UiText(R.string.error_timeout)
        "rejected" -> UiText(R.string.error_rejected)
        "protocol" -> UiText(R.string.error_protocol)
        "cancelled" -> UiText(R.string.error_cancelled)
        "self" -> UiText(R.string.error_self)
        "invalid" -> UiText(R.string.error_invalid)
        "camera-unsupported" -> UiText(R.string.error_camera_unsupported)
        "unsupported" -> UiText(R.string.error_unsupported)
        else -> UiText(R.string.error_unknown, listOf(code))
    }

    /** Status line while connecting to [formattedId]. */
    fun step(step: ConnectStep, formattedId: String): UiText = when (step) {
        ConnectStep.SEARCHING -> UiText(R.string.connect_searching, listOf(formattedId))
        ConnectStep.AUTHENTICATING -> UiText(R.string.connect_authenticating)
        ConnectStep.AUTHENTICATED -> UiText(R.string.connect_authenticated)
        ConnectStep.WAITING_APPROVAL -> UiText(R.string.connect_waiting_approval)
    }
}
