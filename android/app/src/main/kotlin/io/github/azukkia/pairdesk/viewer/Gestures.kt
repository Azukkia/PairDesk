package io.github.azukkia.pairdesk.viewer

import kotlin.math.abs
import kotlin.math.hypot

/** One finger on the screen, in view pixels. */
data class TouchPointer(val id: Int, val x: Float, val y: Float)

enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/**
 * A touch event, independent of android.view.MotionEvent (unit tests).
 * [pointers] are the fingers on the screen during the event; for
 * [TouchAction.POINTER_UP] and [TouchAction.UP] they include the finger
 * lifting ([actionId]), like MotionEvent.
 */
class TouchEvent(
    val action: TouchAction,
    val pointers: List<TouchPointer>,
    val actionId: Int,
    val timeMs: Long,
) {
    override fun toString(): String = "TouchEvent($action, $pointers, id=$actionId, t=$timeMs)"
}

/** How touches drive the remote mouse. */
enum class InputMode(val wire: String) {
    /** The finger is the mouse: tap where to click. */
    DIRECT("direct"),

    /** The screen is a laptop touchpad: one finger moves a cursor, tap clicks where it is. */
    TOUCHPAD("touchpad"),
    ;

    companion object {
        fun fromWire(value: String?): InputMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Distances in view pixels, durations in milliseconds. */
data class GestureConfig(
    /** A finger that moved less than this did not move (ViewConfiguration.getScaledTouchSlop). */
    val touchSlop: Float,
    /** The second tap of a double tap lands within this distance of the first one. */
    val doubleTapSlop: Float,
    /** Pixels per dp (velocities). */
    val density: Float = 1f,
    /** Direct mode: a finger resting this long is a long press (right click, or drag when it moves). */
    val longPressMs: Long = 450,
    /** Longest touch that counts as a tap (touchpad taps, two-finger taps). */
    val tapMaxMs: Long = 250,
    /** Longest pause between the two taps of a double tap. */
    val doubleTapMs: Long = 280,
    /** Touchpad: after a tap, a second touch held this long starts a drag even without moving. */
    val dragHoldMs: Long = 200,
) {
    /** Two fingers whose distance changed this much are pinching (vs scrolling). */
    val pinchSlop: Float get() = touchSlop * 1.5f
}

/**
 * What a touch gesture means, in view pixels. A null `at` means "where the
 * touchpad cursor is". Coordinates are mapped to the remote screen by the
 * listener ([PointerController]).
 */
interface GestureListener {
    /** A first finger touched the screen (stops a fling, hides the toolbar…). */
    fun onTouchStart() = Unit

    /** Direct mode: the finger rested long enough (haptic feedback). */
    fun onLongPress() = Unit

    /** Direct mode: the remote pointer follows the finger (hover, or drag while a button is down). */
    fun onHover(x: Float, y: Float) = Unit

    /** Touchpad mode: the finger moved by ([dx], [dy]) in [dtMs] (cursor, or drag while a button is down). */
    fun onCursorMove(dx: Float, dy: Float, dtMs: Long) = Unit

    /** A click of [button] at [at] (null: at the touchpad cursor). */
    fun onClick(button: Int, at: ViewPoint?) = Unit

    /** [button] goes down at [at] (null: at the cursor): start of a drag. */
    fun onButtonDown(button: Int, at: ViewPoint?) = Unit

    /** [button] goes up at [at] (null: at the cursor): end of a drag. */
    fun onButtonUp(button: Int, at: ViewPoint?) = Unit

    /** Direct mode, zoomed view: one finger drags the picture. */
    fun onPan(dx: Float, dy: Float) = Unit

    /** Two fingers moved by ([dx], [dy]); [focus] is where they started. */
    fun onScroll(dx: Float, dy: Float, focus: ViewPoint) = Unit

    /** The scrolling fingers left the screen at ([vx], [vy]) pixels per second. */
    fun onScrollEnd(vx: Float, vy: Float) = Unit

    fun onPinchStart(focus: ViewPoint) = Unit

    /** [scale]: distance between the fingers relative to the start of the pinch; [focus]: their center now. */
    fun onPinch(scale: Float, focus: ViewPoint) = Unit

    fun onPinchEnd() = Unit
}

/**
 * Turns touches into remote mouse gestures (pure state machine, fed by the
 * view with [onTouch], timers from [scheduler], all on one thread).
 *
 * Direct mode: tap = left click under the finger; double tap = double click
 * (the second click lands exactly on the first); long press = right click;
 * long press then move = left-button drag; one finger moving = pointer follows
 * (hover), or pans the picture when zoomed.
 *
 * Touchpad mode: one finger moves the cursor relatively; tap = left click at
 * the cursor (sent once the double-tap delay has passed); double tap = double
 * click; tap then touch-and-hold / move = left-button drag.
 *
 * Both: two fingers moving = scroll (or pinch = zoom, decided once); two-finger
 * tap = right click; three-finger tap = middle click.
 */
class GestureEngine(
    private val config: GestureConfig,
    private val scheduler: Scheduler,
    private val listener: GestureListener,
    /** Direct mode: one finger pans instead of hovering when the view is zoomed. */
    private val isZoomed: () -> Boolean = { false },
) {
    var mode: InputMode = InputMode.TOUCHPAD
        set(value) {
            if (field == value) return
            reset()
            field = value
        }

    private enum class Phase { IDLE, PRESSED, MOVING, LONG_PRESSED, DRAGGING, MULTI, SCROLLING, PINCHING, IGNORING }

    private var phase = Phase.IDLE
    private var activeId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L

    /** Direct mode, one finger moving: panning the zoomed picture (else hovering). */
    private var panning = false

    private var timer: Cancellable? = null

    // Touchpad: a tap waits for a possible second one before clicking.
    private var pendingTap: Cancellable? = null

    /** Touchpad: this touch began during the double-tap delay of a tap. */
    private var secondTap = false

    // Direct: the last tap, where a quick second tap is snapped (double click).
    private var hasLastTap = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // Several fingers.
    private var multiStartTime = 0L
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var multiStartSpan = 0f
    private var multiLastX = 0f
    private var multiLastY = 0f
    private var multiFingers = 0
    private var multiLifted = false
    private val velocity = VelocityTracker()

    /** True while a gesture is in progress (a finger is down). */
    val isActive: Boolean get() = phase != Phase.IDLE

    fun onTouch(e: TouchEvent) {
        when (e.action) {
            TouchAction.DOWN -> onDown(e)
            TouchAction.POINTER_DOWN -> onPointerDown(e)
            TouchAction.MOVE -> onMove(e)
            TouchAction.POINTER_UP -> onPointerUp(e)
            TouchAction.UP -> onUp(e)
            TouchAction.CANCEL -> cancel()
        }
    }

    /** Ends whatever is in progress (a held button is released) and forgets pending taps. */
    fun reset() {
        cancel()
        pendingTap?.cancel()
        pendingTap = null
        secondTap = false
        hasLastTap = false
    }

    // ───────────────────────────── one finger ─────────────────────────────

    private fun onDown(e: TouchEvent) {
        if (phase != Phase.IDLE) cancel()
        val p = e.pointers.firstOrNull { it.id == e.actionId } ?: e.pointers.firstOrNull() ?: return
        listener.onTouchStart()
        activeId = p.id
        downX = p.x
        downY = p.y
        lastX = p.x
        lastY = p.y
        downTime = e.timeMs
        lastTime = e.timeMs
        panning = false
        phase = Phase.PRESSED
        when (mode) {
            InputMode.DIRECT -> {
                timer = scheduler.schedule(config.longPressMs) {
                    timer = null
                    if (phase == Phase.PRESSED) {
                        phase = Phase.LONG_PRESSED
                        listener.onLongPress()
                    }
                }
            }
            InputMode.TOUCHPAD -> {
                val pending = pendingTap
                secondTap = pending != null
                if (pending != null) {
                    pending.cancel()
                    pendingTap = null
                    // Touch and hold after a tap: a drag, even before the finger moves.
                    timer = scheduler.schedule(config.dragHoldMs) {
                        timer = null
                        if (phase == Phase.PRESSED && secondTap) startTouchpadDrag()
                    }
                }
            }
        }
    }

    private fun startTouchpadDrag() {
        secondTap = false
        phase = Phase.DRAGGING
        listener.onButtonDown(MouseButton.LEFT, null)
    }

    private fun onMove(e: TouchEvent) {
        when (phase) {
            Phase.MULTI, Phase.SCROLLING, Phase.PINCHING -> {
                onMultiMove(e)
                return
            }
            Phase.IDLE, Phase.IGNORING -> return
            else -> Unit
        }
        val p = e.pointers.firstOrNull { it.id == activeId } ?: return
        val dx = p.x - lastX
        val dy = p.y - lastY
        val dt = (e.timeMs - lastTime).coerceAtLeast(0)
        when (phase) {
            Phase.PRESSED -> {
                if (hypot(p.x - downX, p.y - downY) <= config.touchSlop) return
                cancelTimer()
                when (mode) {
                    InputMode.TOUCHPAD -> {
                        if (secondTap) startTouchpadDrag() else phase = Phase.MOVING
                        listener.onCursorMove(dx, dy, dt)
                    }
                    InputMode.DIRECT -> {
                        phase = Phase.MOVING
                        panning = isZoomed()
                        if (panning) listener.onPan(dx, dy) else listener.onHover(p.x, p.y)
                    }
                }
            }
            Phase.MOVING -> when (mode) {
                InputMode.TOUCHPAD -> listener.onCursorMove(dx, dy, dt)
                InputMode.DIRECT -> if (panning) listener.onPan(dx, dy) else listener.onHover(p.x, p.y)
            }
            Phase.LONG_PRESSED -> {
                if (hypot(p.x - downX, p.y - downY) <= config.touchSlop) return
                phase = Phase.DRAGGING
                listener.onButtonDown(MouseButton.LEFT, ViewPoint(downX, downY))
                listener.onHover(p.x, p.y)
            }
            Phase.DRAGGING -> when (mode) {
                InputMode.TOUCHPAD -> listener.onCursorMove(dx, dy, dt)
                InputMode.DIRECT -> listener.onHover(p.x, p.y)
            }
            else -> Unit
        }
        lastX = p.x
        lastY = p.y
        lastTime = e.timeMs
    }

    private fun onUp(e: TouchEvent) {
        cancelTimer()
        val p = e.pointers.firstOrNull { it.id == e.actionId } ?: e.pointers.firstOrNull()
        val x = p?.x ?: lastX
        val y = p?.y ?: lastY
        when (phase) {
            Phase.PRESSED -> onTap(x, y, e.timeMs)
            Phase.LONG_PRESSED -> listener.onClick(MouseButton.RIGHT, ViewPoint(downX, downY))
            Phase.DRAGGING -> listener.onButtonUp(MouseButton.LEFT, if (mode == InputMode.DIRECT) ViewPoint(x, y) else null)
            Phase.MULTI -> multiTap(e.timeMs)
            Phase.SCROLLING -> endScroll(e.timeMs)
            Phase.PINCHING -> listener.onPinchEnd()
            else -> Unit
        }
        secondTap = false
        phase = Phase.IDLE
        activeId = -1
    }

    private fun onTap(x: Float, y: Float, now: Long) {
        when (mode) {
            InputMode.DIRECT -> {
                // A double tap: the second click lands exactly where the first one did,
                // else the computer sees two single clicks a few pixels apart.
                val snap = hasLastTap && downTime - lastTapTime <= config.doubleTapMs &&
                    hypot(x - lastTapX, y - lastTapY) <= config.doubleTapSlop
                val at = if (snap) ViewPoint(lastTapX, lastTapY) else ViewPoint(x, y)
                listener.onClick(MouseButton.LEFT, at)
                if (snap) {
                    hasLastTap = false // a third tap is a new click
                } else {
                    hasLastTap = true
                    lastTapTime = now
                    lastTapX = x
                    lastTapY = y
                }
            }
            InputMode.TOUCHPAD -> {
                if (secondTap) {
                    // Tap, tap: a double click.
                    secondTap = false
                    listener.onClick(MouseButton.LEFT, null)
                    listener.onClick(MouseButton.LEFT, null)
                } else if (now - downTime <= config.tapMaxMs) {
                    pendingTap = scheduler.schedule(config.doubleTapMs) {
                        pendingTap = null
                        listener.onClick(MouseButton.LEFT, null)
                    }
                }
            }
        }
    }

    private fun cancel() {
        cancelTimer()
        when (phase) {
            Phase.DRAGGING -> listener.onButtonUp(MouseButton.LEFT, if (mode == InputMode.DIRECT) ViewPoint(lastX, lastY) else null)
            Phase.SCROLLING -> listener.onScrollEnd(0f, 0f)
            Phase.PINCHING -> listener.onPinchEnd()
            else -> Unit
        }
        if (secondTap) {
            // The first tap of an interrupted double tap still counts.
            secondTap = false
            listener.onClick(MouseButton.LEFT, null)
        }
        phase = Phase.IDLE
        activeId = -1
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    // ───────────────────────────── several fingers ─────────────────────────────

    private fun onPointerDown(e: TouchEvent) {
        when (phase) {
            Phase.PRESSED, Phase.MOVING -> {
                cancelTimer()
                if (secondTap) {
                    // Tap, then two fingers: the tap was a single click after all.
                    secondTap = false
                    listener.onClick(MouseButton.LEFT, null)
                }
                phase = Phase.MULTI
                multiStartTime = downTime
                multiLifted = false
                baseline(e.pointers)
                multiStartX = multiLastX
                multiStartY = multiLastY
                multiStartSpan = span(e.pointers, multiLastX, multiLastY)
                multiFingers = e.pointers.size
            }
            Phase.MULTI -> {
                multiFingers = maxOf(multiFingers, e.pointers.size)
                baseline(e.pointers)
                multiStartX = multiLastX
                multiStartY = multiLastY
                multiStartSpan = span(e.pointers, multiLastX, multiLastY)
            }
            Phase.SCROLLING -> baseline(e.pointers)
            Phase.PINCHING -> {
                listener.onPinchEnd()
                baseline(e.pointers)
                multiStartSpan = span(e.pointers, multiLastX, multiLastY)
                listener.onPinchStart(ViewPoint(multiLastX, multiLastY))
            }
            // A long press or a drag keeps its finger; others are ignored.
            else -> Unit
        }
    }

    private fun onPointerUp(e: TouchEvent) {
        val remaining = e.pointers.filter { it.id != e.actionId }
        when (phase) {
            Phase.MULTI -> {
                if (remaining.size >= 2) {
                    baseline(remaining)
                } else {
                    // Fingers rarely leave at the very same time: the tap is decided on the last one.
                    multiLifted = true
                }
            }
            Phase.SCROLLING -> {
                if (remaining.size >= 2) {
                    baseline(remaining)
                } else {
                    endScroll(e.timeMs)
                    phase = Phase.IGNORING
                }
            }
            Phase.PINCHING -> {
                listener.onPinchEnd()
                if (remaining.size >= 2) {
                    baseline(remaining)
                    multiStartSpan = span(remaining, multiLastX, multiLastY)
                    listener.onPinchStart(ViewPoint(multiLastX, multiLastY))
                } else {
                    phase = Phase.IGNORING
                }
            }
            Phase.LONG_PRESSED, Phase.DRAGGING, Phase.PRESSED, Phase.MOVING -> {
                if (e.actionId != activeId) return
                // The finger of the gesture left, another one stays: the gesture ends here.
                cancelTimer()
                if (phase == Phase.DRAGGING) {
                    listener.onButtonUp(MouseButton.LEFT, if (mode == InputMode.DIRECT) ViewPoint(lastX, lastY) else null)
                }
                secondTap = false
                phase = Phase.IGNORING
            }
            else -> Unit
        }
    }

    private fun onMultiMove(e: TouchEvent) {
        if (multiLifted && phase == Phase.MULTI) {
            // One finger of a two-finger tap left and the other one moves: nothing.
            if (movedFromStart(e.pointers)) phase = Phase.IGNORING
            return
        }
        val (cx, cy) = centroid(e.pointers)
        when (phase) {
            Phase.MULTI -> {
                val s = span(e.pointers, cx, cy)
                val dc = hypot(cx - multiStartX, cy - multiStartY)
                val ds = abs(s - multiStartSpan)
                if (e.pointers.size >= 2 && ds > config.pinchSlop && ds > dc * 0.8f && multiStartSpan > 0f) {
                    phase = Phase.PINCHING
                    listener.onPinchStart(ViewPoint(multiStartX, multiStartY))
                    listener.onPinch(s / multiStartSpan, ViewPoint(cx, cy))
                } else if (dc > config.touchSlop) {
                    phase = Phase.SCROLLING
                    velocity.clear()
                    velocity.add(multiStartX, multiStartY, e.timeMs)
                    velocity.add(cx, cy, e.timeMs)
                    listener.onScroll(cx - multiStartX, cy - multiStartY, ViewPoint(multiStartX, multiStartY))
                } else {
                    return
                }
            }
            Phase.SCROLLING -> {
                velocity.add(cx, cy, e.timeMs)
                listener.onScroll(cx - multiLastX, cy - multiLastY, ViewPoint(multiStartX, multiStartY))
            }
            Phase.PINCHING -> {
                val s = span(e.pointers, cx, cy)
                if (multiStartSpan > 0f) listener.onPinch(s / multiStartSpan, ViewPoint(cx, cy))
            }
            else -> return
        }
        multiLastX = cx
        multiLastY = cy
    }

    private fun movedFromStart(pointers: List<TouchPointer>): Boolean {
        val (cx, cy) = centroid(pointers)
        return hypot(cx - multiLastX, cy - multiLastY) > config.touchSlop
    }

    private fun multiTap(now: Long) {
        if (now - multiStartTime > config.tapMaxMs) return
        val button = when (multiFingers) {
            2 -> MouseButton.RIGHT
            3 -> MouseButton.MIDDLE
            else -> return
        }
        listener.onClick(button, if (mode == InputMode.DIRECT) ViewPoint(multiStartX, multiStartY) else null)
    }

    private fun endScroll(now: Long) {
        val (vx, vy) = velocity.velocity(now)
        listener.onScrollEnd(vx, vy)
    }

    private fun baseline(pointers: List<TouchPointer>) {
        val (cx, cy) = centroid(pointers)
        multiLastX = cx
        multiLastY = cy
    }

    private fun centroid(pointers: List<TouchPointer>): Pair<Float, Float> {
        if (pointers.isEmpty()) return multiLastX to multiLastY
        var sx = 0f
        var sy = 0f
        for (p in pointers) {
            sx += p.x
            sy += p.y
        }
        return sx / pointers.size to sy / pointers.size
    }

    private fun span(pointers: List<TouchPointer>, cx: Float, cy: Float): Float {
        if (pointers.size < 2) return 0f
        var sum = 0f
        for (p in pointers) sum += hypot(p.x - cx, p.y - cy)
        return sum / pointers.size
    }
}

/** Speed of the fingers over their last [WINDOW_MS] of movement (pixels per second). */
class VelocityTracker {
    private val xs = FloatArray(SIZE)
    private val ys = FloatArray(SIZE)
    private val ts = LongArray(SIZE)
    private var count = 0
    private var head = 0

    fun clear() {
        count = 0
        head = 0
    }

    fun add(x: Float, y: Float, timeMs: Long) {
        xs[head] = x
        ys[head] = y
        ts[head] = timeMs
        head = (head + 1) % SIZE
        if (count < SIZE) count++
    }

    /** Velocity when the fingers left at [now]: zero when they had stopped before. */
    fun velocity(now: Long): Pair<Float, Float> {
        if (count < 2) return 0f to 0f
        val last = (head - 1 + SIZE) % SIZE
        if (now - ts[last] > STOPPED_MS) return 0f to 0f
        var first = last
        for (i in 1 until count) {
            val idx = (last - i + SIZE) % SIZE
            if (ts[last] - ts[idx] > WINDOW_MS) break
            first = idx
        }
        val dt = (ts[last] - ts[first]).toFloat()
        if (dt <= 0f) return 0f to 0f
        return (xs[last] - xs[first]) / dt * 1000f to (ys[last] - ys[first]) / dt * 1000f
    }

    private companion object {
        const val SIZE = 16
        const val WINDOW_MS = 100L
        const val STOPPED_MS = 60L
    }
}
