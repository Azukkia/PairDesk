package io.github.azukkia.pairdesk.viewer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where input events go: the session's data channels. */
interface InputSink {
    /** Clicks, keys, wheel, text, resting position: the ordered, reliable `input` channel. */
    fun sendReliable(events: JsonArray)

    /** Mouse moves: the `pointer` channel (unordered, never retransmitted, latest wins). */
    fun sendPointer(events: JsonArray)
}

/** A scheduled task that can be cancelled. */
fun interface Cancellable {
    fun cancel()
}

/** Timers of the input logic, so that tests drive time themselves. Tasks run on the caller's (UI) thread. */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** Mouse buttons of the protocol (MouseEvent.button). */
object MouseButton {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val BACK = 3
    const val FORWARD = 4
}

/**
 * The input events of docs/PROTOCOL.md 4.1, as JSON arrays. Numbers are
 * written like JavaScript's `JSON.stringify` (`0.5`, `1`, never `1.0E-4`).
 */
@OptIn(ExperimentalSerializationApi::class)
object InputEvents {
    /** A coordinate rounded to 4 decimals, written as JavaScript does. */
    fun coord(v: Double): JsonPrimitive {
        val n = (RemotePoint.round4(v) * 10_000).roundToLong()
        val sign = if (n < 0) "-" else ""
        val a = abs(n)
        val int = a / 10_000
        val frac = a % 10_000
        val text = if (frac == 0L) "$sign$int" else sign + int + "." + frac.toString().padStart(4, '0').trimEnd('0')
        return if (text == "-0") JsonPrimitive(0) else JsonUnquotedLiteral(text)
    }

    private fun arr(vararg items: Any): JsonArray = JsonArray(
        items.map { v ->
            when (v) {
                is JsonElement -> v
                is String -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                else -> throw IllegalArgumentException("unsupported input value $v")
            }
        },
    )

    /** `['m', x, y, seq?, btnSeq?]`. */
    fun move(p: RemotePoint, seq: Long? = null, btnSeq: Long? = null): JsonArray = when {
        seq == null -> arr("m", coord(p.x), coord(p.y))
        btnSeq == null -> arr("m", coord(p.x), coord(p.y), seq)
        else -> arr("m", coord(p.x), coord(p.y), seq, btnSeq)
    }

    /** `['d', button, x, y, seq?]`. */
    fun down(button: Int, p: RemotePoint, seq: Long? = null): JsonArray =
        if (seq == null) arr("d", button, coord(p.x), coord(p.y)) else arr("d", button, coord(p.x), coord(p.y), seq)

    /** `['u', button, x?, y?, seq?]`. */
    fun up(button: Int, p: RemotePoint? = null, seq: Long? = null): JsonArray = when {
        p == null -> arr("u", button)
        seq == null -> arr("u", button, coord(p.x), coord(p.y))
        else -> arr("u", button, coord(p.x), coord(p.y), seq)
    }

    /** `['w', dx, dy, seq?]` (pixels, positive dy scrolls down). */
    fun wheel(dx: Int, dy: Int, seq: Long? = null): JsonArray = if (seq == null) arr("w", dx, dy) else arr("w", dx, dy, seq)

    /** `['k', code, 1|0]`. */
    fun key(code: String, down: Boolean): JsonArray = arr("k", code, if (down) 1 else 0)

    /** `['t', text]` (≤ [MAX_TEXT] UTF-16 units, see [chunkText]). */
    fun text(text: String): JsonArray = arr("t", text)

    /** `['r']`: release every pressed key and button. */
    fun releaseAll(): JsonArray = arr("r")

    /** `['a', action]` (Android hosts: back, home, recents, notifications). */
    fun action(name: String): JsonArray = arr("a", name)

    /** The host types at most 256 characters per `t` event (input-core.js). */
    const val MAX_TEXT = 256

    /** Splits [text] in pieces of at most [max] UTF-16 units, never inside a surrogate pair. */
    fun chunkText(text: String, max: Int = MAX_TEXT): List<String> {
        require(max >= 2)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + max)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    /** Wheel deltas are clamped by the host (input-core.js `clampWheel`). */
    const val MAX_WHEEL = 2400
}

/**
 * Sends the controller's input with the sequencing rules of the desktop
 * viewer (src/renderer/viewer/viewer.js `sendMove`, `sendReliable` and the
 * mouse handlers):
 *
 * - moves go out at once on the `pointer` channel as `['m', x, y, seq, btnSeq]`;
 *   when the pointer rests for [SETTLE_MS], its position is sent again on the
 *   reliable channel with a fresh `seq` (in case the last move was lost);
 * - button presses / releases and wheel events are numbered too and become the
 *   `btnSeq` of the following moves (the host drops moves that overtook them);
 * - a wheel event is preceded by a reliable move to its position;
 * - `seq` keeps increasing for the life of the session, across reconnections.
 *
 * Not thread-safe: call it from one thread (the UI thread).
 */
class InputSender(private val sink: InputSink, private val scheduler: Scheduler) {
    /** Last sequence number used (viewer.js `pointerSeq`). */
    var seq: Long = 0L
        private set

    private var lastButtonSeq = 0L
    private var lastMove: RemotePoint? = null
    private var settle: Cancellable? = null
    private val pressedButtons = LinkedHashSet<Int>()
    private val pressedKeys = LinkedHashSet<String>()

    /** Buttons currently held on the remote. */
    val buttons: Set<Int> get() = pressedButtons

    /** Keys currently held on the remote. */
    val keys: Set<String> get() = pressedKeys

    /** The last position sent (the remote pointer), if any. */
    val position: RemotePoint? get() = lastMove

    fun move(p: RemotePoint) {
        if (lastMove == p) return
        lastMove = p
        val s = ++seq
        sink.sendPointer(JsonArray(listOf(InputEvents.move(p, s, lastButtonSeq))))
        settle?.cancel()
        settle = scheduler.schedule(SETTLE_MS) {
            settle = null
            if (lastMove != p) return@schedule
            // New number: the reliable copy queues behind any late click and must
            // not be dropped as a duplicate of the lost move.
            sink.sendReliable(JsonArray(listOf(InputEvents.move(p, ++seq))))
        }
    }

    fun press(button: Int, p: RemotePoint) {
        pressedButtons += button
        lastMove = p
        val s = ++seq
        lastButtonSeq = s
        sink.sendReliable(JsonArray(listOf(InputEvents.down(button, p, s))))
    }

    /** Releases [button], at [p] when known (else where the pointer is). */
    fun release(button: Int, p: RemotePoint?) {
        pressedButtons -= button
        if (p == null) {
            sink.sendReliable(JsonArray(listOf(InputEvents.up(button))))
            return
        }
        lastMove = p
        val s = ++seq
        lastButtonSeq = s
        sink.sendReliable(JsonArray(listOf(InputEvents.up(button, p, s))))
    }

    /** A click: press then release at the same point. */
    fun click(button: Int, p: RemotePoint) {
        press(button, p)
        release(button, p)
    }

    /** Wheel of ([dx], [dy]) pixels at [p] (positive dy scrolls down); nothing when both are 0. */
    fun wheel(dx: Int, dy: Int, p: RemotePoint?) {
        val x = dx.coerceIn(-InputEvents.MAX_WHEEL, InputEvents.MAX_WHEEL)
        val y = dy.coerceIn(-InputEvents.MAX_WHEEL, InputEvents.MAX_WHEEL)
        if (x == 0 && y == 0) return
        if (p == null) {
            sink.sendReliable(JsonArray(listOf(InputEvents.wheel(x, y))))
            return
        }
        // Scroll where the pointer is, even if its last move was lost: the position
        // goes first on the same reliable channel. Like a click, the wheel is
        // numbered: moves made after it wait for it.
        lastMove = p
        val s = ++seq
        lastButtonSeq = s
        settle?.cancel()
        settle = null
        sink.sendReliable(JsonArray(listOf(InputEvents.move(p, s), InputEvents.wheel(x, y, s))))
    }

    /** One key event (`code` = KeyboardEvent.code). */
    fun key(code: String, down: Boolean) {
        if (down) pressedKeys += code else pressedKeys -= code
        sink.sendReliable(JsonArray(listOf(InputEvents.key(code, down))))
    }

    /** Several key events in one message (combinations), tracking what stays pressed. */
    fun keys(events: List<Pair<String, Boolean>>) {
        if (events.isEmpty()) return
        for ((code, down) in events) if (down) pressedKeys += code else pressedKeys -= code
        sink.sendReliable(JsonArray(events.map { (code, down) -> InputEvents.key(code, down) }))
    }

    /** A key combination: every key down in order, then up in reverse order (viewer.js `sendCombo`). */
    fun combo(codes: List<String>) {
        keys(codes.map { it to true } + codes.reversed().map { it to false })
    }

    /** Types Unicode [text] (`['t', …]` pieces of ≤ 256 characters). */
    fun text(text: String) {
        if (text.isEmpty()) return
        val pieces = InputEvents.chunkText(text)
        for (batch in pieces.chunked(TEXT_EVENTS_PER_MESSAGE)) {
            sink.sendReliable(JsonArray(batch.map(InputEvents::text)))
        }
    }

    /** `['a', action]` for Android hosts. */
    fun action(name: String) {
        sink.sendReliable(JsonArray(listOf(InputEvents.action(name))))
    }

    /** Releases everything held on the remote (`['r']`, only when something is held, like viewer.js). */
    fun releaseAll() {
        if (pressedKeys.isNotEmpty() || pressedButtons.isNotEmpty()) sink.sendReliable(JsonArray(listOf(InputEvents.releaseAll())))
        pressedKeys.clear()
        pressedButtons.clear()
    }

    /** Forgets what was held without telling the host (the connection is gone). */
    fun forgetPressed() {
        pressedKeys.clear()
        pressedButtons.clear()
    }

    /**
     * New peer connection (viewer.js `startSession`): `seq` keeps increasing,
     * a host session that outlived the connection must not take new moves for
     * stale ones.
     */
    fun resetForNewConnection() {
        settle?.cancel()
        settle = null
        lastButtonSeq = 0
        lastMove = null
    }

    fun dispose() {
        settle?.cancel()
        settle = null
    }

    companion object {
        /** Rest time after which the pointer position is resent reliably (viewer.js). */
        const val SETTLE_MS = 120L

        private const val TEXT_EVENTS_PER_MESSAGE = 16
    }
}
