package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.jsNumberOrNull
import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.jsTruthy
import io.github.azukkia.pairdesk.viewer.Cancellable
import io.github.azukkia.pairdesk.viewer.MouseButton
import io.github.azukkia.pairdesk.viewer.Scheduler
import kotlinx.serialization.json.JsonArray
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** A point of the phone's screen, in pixels of its current orientation. */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * One finger of an injected gesture: the polyline [points] followed in
 * [durationMs] (a single point is a tap, or a long press when long enough).
 */
data class Stroke(val points: List<ScreenPoint>, val durationMs: Long)

/** Navigation of the phone (`['a', action]`, AccessibilityService global actions). */
enum class NavAction(val wire: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    ;

    companion object {
        fun fromWire(value: String?): NavAction? = entries.firstOrNull { it.wire == value }
    }
}

/** Cursor moves in the focused text field. */
enum class CaretMove { LEFT, RIGHT, UP, DOWN, HOME, END }

/** What a key or a text event does in the focused text field. */
sealed interface EditCommand {
    data class Type(val text: String) : EditCommand

    data object Enter : EditCommand

    data object Backspace : EditCommand

    data object Delete : EditCommand

    data object Tab : EditCommand

    /** [extend]: Shift is held, the selection grows. */
    data class Move(val move: CaretMove, val extend: Boolean) : EditCommand

    data object SelectAll : EditCommand

    data object Copy : EditCommand

    data object Cut : EditCommand

    data object Paste : EditCommand
}

/** The accessibility service, seen from [HostInput]. Called on the input thread. */
interface InputInjector {
    /**
     * Dispatches a one-finger gesture. [onDone] runs on the input thread once
     * it completed or was cancelled; false (and no callback) when it could not
     * be dispatched.
     */
    fun dispatch(stroke: Stroke, onDone: () -> Unit): Boolean

    fun navigate(action: NavAction): Boolean

    fun edit(command: EditCommand): Boolean
}

/** Gesture timings and sizes of the phone (ViewConfiguration). */
data class HostInputConfig(
    /** Movement under which a press stays a tap (pixels). */
    val touchSlop: Float = 24f,
    /** Hold time from which Android sees a long press. */
    val longPressMs: Long = 500,
    /** Swipe pixels per wheel pixel (the desktop sends ~120 per notch). */
    val wheelScale: Float = 2f,
)

/**
 * The remote input of a computer controlling this phone (docs/PROTOCOL.md 4.1
 * and 5.2), platform-free: the input events of the viewer become gestures,
 * navigation actions and text edits for the accessibility service.
 *
 * - Left button: press, moves and release make one finger stroke, injected
 *   when the button is released with the time it was held (a click is a tap,
 *   a long click a long press, a drag a swipe of the same speed). Android
 *   cancels a gesture when a new one starts, so gestures are queued: each one
 *   is dispatched once the previous one is over.
 * - Right button: long press (context menu); back button: Back.
 * - Wheel: a swipe from the pointer position, the opposite way (wheel events
 *   arriving while a gesture runs are added up into the next swipe).
 * - Keys: Enter, Backspace, Delete, arrows, Home / End, Tab and the
 *   Ctrl (or Cmd) + A / C / X / V shortcuts edit the focused field; Escape is
 *   Back. Text (`['t', …]`) is typed into the focused field.
 * - `['a', …]`: Back, Home, Recent apps, notifications.
 *
 * Out-of-order moves are dropped like on the desktop ([PointerSequencer]).
 * Not thread-safe: one thread (the input thread), timers from [scheduler].
 */
class HostInput(
    /** Null while the accessibility service is not running. */
    private val injector: () -> InputInjector?,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val config: HostInputConfig = HostInputConfig(),
) {
    private val sequencer = PointerSequencer()

    /** False: view-only session (events are only counted by the sequencer). */
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) releaseAll()
        }

    private var width = 0
    private var height = 0

    /** Last pointer position, in pixels. */
    var pointer: ScreenPoint? = null
        private set

    private class Drag(val start: Long, val points: ArrayList<ScreenPoint>)

    private var drag: Drag? = null
    private val modifiers = HashSet<String>()

    private val queue = ArrayDeque<Stroke>()
    private var inFlight = 0L
    private var nextGesture = 1L
    private var watchdog: Cancellable? = null

    private var wheelX = 0.0
    private var wheelY = 0.0
    private var wheelAt: ScreenPoint? = null

    /** Gestures waiting for the one being injected (tests). */
    val queued: Int get() = queue.size

    /** True while a gesture is being injected. */
    val busy: Boolean get() = inFlight != 0L

    /** Size of the phone's screen in its current orientation (pixels). */
    fun setScreenSize(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        // A drag across a rotation would end in another coordinate space.
        drag = null
        pointer = pointer?.let(::clamp)
    }

    /** A new peer connection: sequence numbers start again. */
    fun reset() {
        sequencer.reset()
        releaseAll()
    }

    /** One message of the `input` or `pointer` channel. */
    fun onEvents(events: JsonArray) {
        for (e in events.take(MAX_EVENTS)) {
            val ev = e as? JsonArray ?: continue
            if (!sequencer.accept(ev)) continue
            if (!enabled || width <= 0 || height <= 0) continue
            apply(ev)
        }
    }

    private fun apply(ev: JsonArray) {
        when (ev.getOrNull(0).jsStringOrNull()) {
            "m" -> point(ev, 1)?.let(::moveTo)
            "d" -> {
                if (ev.size >= 4) point(ev, 2)?.let { pointer = it }
                ev.getOrNull(1).jsSafeIntegerOrNull()?.let { press(it.toInt()) }
            }
            "u" -> {
                if (ev.size >= 4) point(ev, 2)?.let(::moveTo)
                ev.getOrNull(1).jsSafeIntegerOrNull()?.let { release(it.toInt()) }
            }
            "w" -> wheel(number(ev.getOrNull(1)), number(ev.getOrNull(2)))
            "k" -> {
                val code = ev.getOrNull(1).jsStringOrNull() ?: return
                if (code.length < 32) key(code, ev.getOrNull(2).jsTruthy())
            }
            "t" -> {
                val text = ev.getOrNull(1).jsStringOrNull() ?: return
                if (text.isNotEmpty()) edit(EditCommand.Type(text.take(MAX_TEXT)))
            }
            "a" -> NavAction.fromWire(ev.getOrNull(1).jsStringOrNull())?.let(::navigate)
            "r" -> releaseAll()
            else -> Unit
        }
    }

    private fun number(e: kotlinx.serialization.json.JsonElement?): Double =
        e.jsNumberOrNull()?.takeIf { it.isFinite() }?.coerceIn(-MAX_WHEEL, MAX_WHEEL) ?: 0.0

    /** Normalized `x, y` at [index] → pixels (input-core.js: `clamp01(v) * (size - 1)`). */
    private fun point(ev: JsonArray, index: Int): ScreenPoint? {
        val x = ev.getOrNull(index).jsNumberOrNull() ?: return null
        val y = ev.getOrNull(index + 1).jsNumberOrNull() ?: return null
        return ScreenPoint(clamp01(x) * (width - 1), clamp01(y) * (height - 1))
    }

    private fun clamp01(v: Double): Float = if (v.isFinite()) v.coerceIn(0.0, 1.0).toFloat() else 0f

    private fun clamp(p: ScreenPoint) = ScreenPoint(
        p.x.coerceIn(0f, max(0, width - 1).toFloat()),
        p.y.coerceIn(0f, max(0, height - 1).toFloat()),
    )

    // ───────────────────────────── pointer ─────────────────────────────

    private fun moveTo(p: ScreenPoint) {
        pointer = p
        val d = drag ?: return
        val last = d.points.last()
        if (hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) < MIN_STEP_PX) return
        if (d.points.size < MAX_POINTS) d.points += p else d.points[d.points.size - 1] = p
    }

    private fun press(button: Int) {
        val p = pointer ?: ScreenPoint((width - 1) / 2f, (height - 1) / 2f)
        when (button) {
            MouseButton.LEFT -> {
                finishDrag()
                drag = Drag(clock(), arrayListOf(p))
            }
            // A right click opens what a long press opens on a phone.
            MouseButton.RIGHT -> enqueue(Stroke(listOf(p), config.longPressMs + LONG_PRESS_MARGIN_MS))
            MouseButton.BACK -> navigate(NavAction.BACK)
            else -> Unit
        }
    }

    private fun release(button: Int) {
        if (button == MouseButton.LEFT) finishDrag()
    }

    /** The left button went up (or everything is released): injects the stroke it drew. */
    private fun finishDrag() {
        val d = drag ?: return
        drag = null
        val held = clock() - d.start
        val start = d.points.first()
        val far = d.points.any { hypot((it.x - start.x).toDouble(), (it.y - start.y).toDouble()) > config.touchSlop / 2 }
        val stroke = when {
            far -> Stroke(d.points.toList(), held.coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS))
            // Held still: a tap, or a long press as long as the click.
            held >= config.longPressMs -> Stroke(listOf(start), held.coerceAtMost(MAX_GESTURE_MS))
            else -> Stroke(listOf(start), TAP_MS)
        }
        enqueue(stroke)
    }

    private fun wheel(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        wheelX += dx
        wheelY += dy
        wheelAt = pointer
        if (!busy && queue.isEmpty()) dispatchNext()
    }

    /** Turns the wheel added up so far into a swipe (at most [MAX_SWIPE_FRACTION] of the screen; the rest waits). */
    private fun wheelStroke(): Stroke? {
        if (wheelX == 0.0 && wheelY == 0.0) return null
        val scale = config.wheelScale.toDouble()
        val maxX = width * MAX_SWIPE_FRACTION
        val maxY = height * MAX_SWIPE_FRACTION
        // The finger moves the other way: scrolling down pushes the content up.
        var fx = (-wheelX * scale).coerceIn(-maxX, maxX)
        var fy = (-wheelY * scale).coerceIn(-maxY, maxY)
        wheelX += fx / scale
        wheelY += fy / scale
        if (abs(wheelX) < 0.5) wheelX = 0.0
        if (abs(wheelY) < 0.5) wheelY = 0.0
        // Android starts scrolling after the touch slop: added along the main axis.
        if (abs(fy) >= abs(fx)) fy += sign(fy) * config.touchSlop else fx += sign(fx) * config.touchSlop
        val from = wheelAt ?: ScreenPoint((width - 1) / 2f, (height - 1) / 2f)
        val sx = startFor(from.x, fx, width)
        val sy = startFor(from.y, fy, height)
        val end = clamp(ScreenPoint((sx + fx).toFloat(), (sy + fy).toFloat()))
        val begin = ScreenPoint(sx.toFloat(), sy.toFloat())
        if (begin == end) return null
        return Stroke(listOf(begin, end), WHEEL_SWIPE_MS)
    }

    /** A start coordinate near [at] that leaves room for a move of [delta] within [size]. */
    private fun startFor(at: Float, delta: Double, size: Int): Double {
        val last = (size - 1).toDouble()
        val lo = if (delta < 0) min(-delta, last) else 0.0
        val hi = if (delta > 0) max(last - delta, 0.0) else last
        return at.toDouble().coerceIn(lo, max(lo, hi))
    }

    // ───────────────────────────── gesture queue ─────────────────────────────

    private fun enqueue(stroke: Stroke) {
        // Wheel added up before this gesture goes first.
        wheelStroke()?.let { queue.addLast(it) }
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(stroke)
        dispatchNext()
    }

    private fun dispatchNext() {
        if (busy) return
        while (true) {
            val stroke = queue.removeFirstOrNull() ?: wheelStroke() ?: return
            val target = injector() ?: run {
                queue.clear()
                return
            }
            val id = nextGesture++
            inFlight = id
            if (target.dispatch(stroke) { onGestureDone(id) }) {
                // Should the service never call back, the queue must not stall.
                watchdog = scheduler.schedule(stroke.durationMs + WATCHDOG_MS) { onGestureDone(id) }
                return
            }
            inFlight = 0L
        }
    }

    private fun onGestureDone(id: Long) {
        if (inFlight != id) return
        inFlight = 0L
        watchdog?.cancel()
        watchdog = null
        dispatchNext()
    }

    // ───────────────────────────── keys, text, navigation ─────────────────────────────

    private fun key(code: String, down: Boolean) {
        if (code in MODIFIERS) {
            if (down) modifiers += code else modifiers -= code
            return
        }
        if (!down) return
        val shortcut = modifiers.any { it.startsWith("Control") || it.startsWith("Meta") }
        val shift = modifiers.any { it.startsWith("Shift") }
        val command: EditCommand = when (code) {
            "Enter", "NumpadEnter" -> EditCommand.Enter
            "Backspace" -> EditCommand.Backspace
            "Delete" -> EditCommand.Delete
            "Tab" -> EditCommand.Tab
            "ArrowLeft" -> EditCommand.Move(CaretMove.LEFT, shift)
            "ArrowRight" -> EditCommand.Move(CaretMove.RIGHT, shift)
            "ArrowUp" -> EditCommand.Move(CaretMove.UP, shift)
            "ArrowDown" -> EditCommand.Move(CaretMove.DOWN, shift)
            "Home" -> EditCommand.Move(CaretMove.HOME, shift)
            "End" -> EditCommand.Move(CaretMove.END, shift)
            "KeyA" -> if (shortcut) EditCommand.SelectAll else return
            "KeyC" -> if (shortcut) EditCommand.Copy else return
            "KeyX" -> if (shortcut) EditCommand.Cut else return
            "KeyV" -> if (shortcut) EditCommand.Paste else return
            "Escape", "BrowserBack" -> return navigate(NavAction.BACK)
            "BrowserHome" -> return navigate(NavAction.HOME)
            else -> return
        }
        edit(command)
    }

    private fun edit(command: EditCommand) {
        injector()?.edit(command)
    }

    private fun navigate(action: NavAction) {
        injector()?.navigate(action)
    }

    /** `['r']`, control withdrawn, connection lost: a held button ends its stroke, modifiers are released. */
    fun releaseAll() {
        modifiers.clear()
        if (enabled) finishDrag() else drag = null
    }

    fun dispose() {
        watchdog?.cancel()
        watchdog = null
        queue.clear()
        drag = null
        inFlight = 0L
    }

    companion object {
        /** The desktop applies at most 200 events per message (input-core.js). */
        const val MAX_EVENTS = 200
        const val MAX_TEXT = 256
        const val MAX_WHEEL = 2400.0

        /** A click: short enough for a tap whatever the click lasted. */
        const val TAP_MS = 60L
        const val MIN_SWIPE_MS = 40L
        const val LONG_PRESS_MARGIN_MS = 300L
        const val WHEEL_SWIPE_MS = 220L

        /** GestureDescription.getMaxGestureDuration() is 60 s. */
        const val MAX_GESTURE_MS = 59_000L
        const val MAX_POINTS = 256
        const val MIN_STEP_PX = 2.0
        const val MAX_SWIPE_FRACTION = 0.45
        const val MAX_QUEUE = 32
        const val WATCHDOG_MS = 1_500L

        private val MODIFIERS = setOf(
            "ControlLeft", "ControlRight", "ShiftLeft", "ShiftRight",
            "AltLeft", "AltRight", "MetaLeft", "MetaRight",
        )
    }
}
