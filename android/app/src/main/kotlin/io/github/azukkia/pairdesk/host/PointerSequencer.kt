package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import kotlinx.serialization.json.JsonArray

/**
 * Drops pointer moves that arrive out of order (src/main/input/pointer-seq.js).
 *
 * Moves travel on the unordered `pointer` channel, clicks, wheel and the
 * resting position on the reliable `input` channel. Every position-bearing
 * event carries a sequence number: a move older than the last applied
 * position is dropped, and so is a move whose `btnSeq` (the last press /
 * release / wheel sent before it) has not been applied yet — it overtook a
 * click still being retransmitted. Events without numbers (viewers older
 * than 1.1) are applied as they come.
 */
class PointerSequencer {
    private var last = -1L
    private var button = 0L

    fun reset() {
        last = -1L
        button = 0L
    }

    /** True when [event] must be applied. */
    fun accept(event: JsonArray): Boolean {
        when (event.getOrNull(0).jsStringOrNull()) {
            "m" -> {
                val seq = event.getOrNull(3).jsSafeIntegerOrNull() ?: return true
                if (seq <= last) return false
                val btnSeq = event.getOrNull(4).jsSafeIntegerOrNull()
                if (btnSeq != null && btnSeq > button) return false
                last = seq
                return true
            }
            "d", "u", "w" -> {
                val index = if (event.getOrNull(0).jsStringOrNull() == "w") 3 else 4
                val seq = event.getOrNull(index).jsSafeIntegerOrNull()
                if (seq != null) {
                    if (seq > last) last = seq
                    if (seq > button) button = seq
                }
                return true
            }
            else -> return true
        }
    }
}
