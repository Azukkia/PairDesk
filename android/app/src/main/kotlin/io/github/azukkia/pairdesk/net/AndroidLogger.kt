package io.github.azukkia.pairdesk.net

import android.util.Log
import io.github.azukkia.pairdesk.core.transport.Logger

/** Diagnostics of :core and of the app, in logcat under the `PairDesk` tag. */
object AndroidLogger : Logger {
    private const val TAG = "PairDesk"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }
}
