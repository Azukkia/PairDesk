package io.github.azukkia.pairdesk.viewer

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the phone shows of the remote screen: the view and picture sizes
 * ([geometry]), the zoom and pan ([zoom]), the input mode and the touchpad
 * cursor (normalized, unrounded, so that small finger moves add up). Lives
 * with the session (survives the view being recreated); main thread only.
 */
class Viewport(mode: InputMode = InputMode.TOUCHPAD) {
    var geometry: ViewGeometry = ViewGeometry(0f, 0f, 0, 0)
        private set

    var zoom: ViewZoom = ViewZoom.FIT
        private set

    var mode: InputMode = mode
        set(value) {
            if (field == value) return
            field = value
            changed(layout = false)
        }

    var cursorX: Double = 0.5
        private set

    var cursorY: Double = 0.5
        private set

    private val listeners = CopyOnWriteArrayList<(layout: Boolean) -> Unit>()

    /** [listener] is called after every change; `layout` is true when the picture moved or was resized. */
    fun addListener(listener: (layout: Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (layout: Boolean) -> Unit) {
        listeners -= listener
    }

    private fun changed(layout: Boolean = true) {
        for (l in listeners) l(layout)
    }

    val isReady: Boolean get() = geometry.isReady

    fun setViewSize(width: Int, height: Int) {
        if (geometry.viewWidth == width.toFloat() && geometry.viewHeight == height.toFloat()) return
        geometry = ViewGeometry(width.toFloat(), height.toFloat(), geometry.frameWidth, geometry.frameHeight)
        zoom = if (geometry.isReady) geometry.normalize(zoom) else zoom
        changed()
    }

    /** Size of the decoded picture (the host may lower its resolution: the zoom is kept, it is normalized). */
    fun setFrameSize(width: Int, height: Int) {
        if (geometry.frameWidth == width && geometry.frameHeight == height) return
        geometry = ViewGeometry(geometry.viewWidth, geometry.viewHeight, width, height)
        zoom = if (geometry.isReady) geometry.normalize(zoom) else zoom
        changed()
    }

    fun setZoom(z: ViewZoom) {
        val next = if (geometry.isReady) geometry.normalize(z) else ViewZoom.FIT
        if (next == zoom) return
        zoom = next
        changed()
    }

    fun resetZoom() = setZoom(ViewZoom.FIT)

    /** Where the picture is drawn, null before the first frame. */
    fun placement(): Placement? = if (geometry.isReady) geometry.placement(zoom) else null

    fun setCursor(x: Double, y: Double) {
        val nx = x.coerceIn(0.0, 1.0)
        val ny = y.coerceIn(0.0, 1.0)
        if (nx == cursorX && ny == cursorY) return
        cursorX = nx
        cursorY = ny
        changed(layout = false)
    }

    val cursor: RemotePoint get() = RemotePoint.of(cursorX, cursorY)

    /** The cursor in view pixels, null before the first frame. */
    fun cursorInView(): ViewPoint? = if (geometry.isReady) geometry.toView(zoom, cursorX, cursorY) else null

    /** Pans (when zoomed) so that the cursor stays [margin] pixels inside the view. */
    fun follow(margin: Float) {
        if (!geometry.isReady || !zoom.isZoomed) return
        setZoom(geometry.ensureVisible(zoom, cursorX, cursorY, margin))
    }

    fun toRemote(x: Float, y: Float, clamp: Boolean = false): RemotePoint? = geometry.toRemote(zoom, x, y, clamp)

    /** View pixels per remote pixel, for a remote screen [remoteWidth] pixels wide (else the picture's width). */
    fun pixelScale(remoteWidth: Int?): Float {
        val p = placement() ?: return 0f
        val w = remoteWidth?.takeIf { it > 0 } ?: geometry.frameWidth
        return if (w > 0) p.width / w else 0f
    }
}

/**
 * Touchpad pointer acceleration: slow finger moves are precise (gain
 * [minGain]), fast ones cross the screen (gain up to [maxGain]), with a smooth
 * curve between [slowDpPerMs] and [fastDpPerMs].
 */
class TouchpadAcceleration(
    val minGain: Float = 1f,
    val maxGain: Float = 3f,
    val slowDpPerMs: Float = 0.1f,
    val fastDpPerMs: Float = 1.6f,
) {
    fun gain(speedDpPerMs: Float): Float {
        val t = ((speedDpPerMs - slowDpPerMs) / (fastDpPerMs - slowDpPerMs)).coerceIn(0f, 1f)
        val smooth = t * t * (3 - 2 * t)
        return minGain + (maxGain - minGain) * smooth
    }
}

/** Sticky modifiers held around mouse clicks (Ctrl + click…), see [KeyboardController]. */
interface ModifierHold {
    /** Presses the active sticky modifiers on the remote and returns their codes (to release them later). */
    fun pressModifiers(): List<String>

    fun releaseModifiers(codes: List<String>)
}

/**
 * Turns gestures ([GestureEngine]) and a physical mouse into remote input
 * events: positions mapped through the [viewport] (letterboxing, zoom, pan),
 * the touchpad cursor moved with acceleration, two-finger scrolling turned
 * into wheel events in the natural direction (with a fling), pinch zoom.
 * Main thread only.
 */
class PointerController(
    private val viewport: Viewport,
    private val sender: InputSender,
    private val modifiers: ModifierHold,
    private val scheduler: Scheduler,
    /** Pixels per dp. */
    private val density: Float,
    /** Input is sent only while true (connected, control allowed). */
    private val canControl: () -> Boolean,
    /** Width of the remote screen in its own pixels, when known (scroll distances). */
    private val remoteWidth: () -> Int? = { null },
    private val acceleration: TouchpadAcceleration = TouchpadAcceleration(),
    /** A finger touched the view. */
    private val onTouch: () -> Unit = {},
    /** Long press recognized (haptic feedback). */
    private val onLongPressFeedback: () -> Unit = {},
) : GestureListener {
    private var dragButton: Int? = null
    private var dragModifiers: List<String> = emptyList()

    private var wheelX = 0.0
    private var wheelY = 0.0
    private var scrollAt: RemotePoint? = null
    private var fling: Cancellable? = null

    private var pinchZoom = ViewZoom.FIT
    private var pinchAnchorX = 0.5
    private var pinchAnchorY = 0.5
    private var pinchAt = ViewPoint(0f, 0f)

    private var smoothSpeed = 0f

    // ───────────────────────────── gestures ─────────────────────────────

    override fun onTouchStart() {
        stopFling()
        onTouch()
    }

    override fun onLongPress() {
        if (canControl()) onLongPressFeedback()
    }

    override fun onHover(x: Float, y: Float) {
        // While a button is held the pointer may leave the picture (clamped), like the desktop.
        val p = viewport.toRemote(x, y, clamp = dragButton != null) ?: return
        viewport.setCursor(p.x, p.y)
        if (canControl()) sender.move(p)
    }

    override fun onCursorMove(dx: Float, dy: Float, dtMs: Long) {
        val placement = viewport.placement() ?: return
        val speed = hypot(dx, dy) / density / max(dtMs, 4L).toFloat()
        smoothSpeed = if (dtMs > 100) speed else smoothSpeed * 0.4f + speed * 0.6f
        val gain = acceleration.gain(smoothSpeed)
        viewport.setCursor(viewport.cursorX + gain * dx / placement.width, viewport.cursorY + gain * dy / placement.height)
        viewport.follow(FOLLOW_MARGIN_DP * density)
        if (canControl()) sender.move(viewport.cursor)
    }

    override fun onClick(button: Int, at: ViewPoint?) {
        if (!canControl()) return
        val p = target(at, clamp = false) ?: return
        if (at != null) viewport.setCursor(p.x, p.y)
        val mods = modifiers.pressModifiers()
        sender.click(button, p)
        modifiers.releaseModifiers(mods)
    }

    override fun onButtonDown(button: Int, at: ViewPoint?) {
        if (!canControl() || dragButton != null) return
        val p = target(at, clamp = false) ?: return
        if (at != null) viewport.setCursor(p.x, p.y)
        dragModifiers = modifiers.pressModifiers()
        dragButton = button
        sender.press(button, p)
    }

    override fun onButtonUp(button: Int, at: ViewPoint?) {
        if (dragButton != button) return
        dragButton = null
        val p = target(at, clamp = true)
        if (canControl()) {
            sender.release(button, p)
            modifiers.releaseModifiers(dragModifiers)
        }
        dragModifiers = emptyList()
    }

    override fun onPan(dx: Float, dy: Float) {
        if (!viewport.isReady) return
        viewport.setZoom(viewport.geometry.panBy(viewport.zoom, dx, dy))
    }

    override fun onScroll(dx: Float, dy: Float, focus: ViewPoint) {
        stopFling()
        scrollAt = if (viewport.mode == InputMode.TOUCHPAD) viewport.cursor else viewport.toRemote(focus.x, focus.y, clamp = true)
        scroll(dx, dy)
    }

    override fun onScrollEnd(vx: Float, vy: Float) {
        val speed = hypot(vx, vy) / density
        if (speed < MIN_FLING_DP_PER_S || !canControl()) {
            wheelX = 0.0
            wheelY = 0.0
            return
        }
        startFling(vx, vy)
    }

    override fun onPinchStart(focus: ViewPoint) {
        stopFling()
        if (!viewport.isReady) return
        pinchZoom = viewport.zoom
        if (viewport.mode == InputMode.TOUCHPAD) {
            // Zoom on the cursor: it stays where it is on the screen.
            pinchAnchorX = viewport.cursorX
            pinchAnchorY = viewport.cursorY
            pinchAt = viewport.cursorInView() ?: focus
        } else {
            val (nx, ny) = viewport.geometry.toNormalized(pinchZoom, focus.x, focus.y) ?: return
            pinchAnchorX = nx
            pinchAnchorY = ny
            pinchAt = focus
        }
    }

    override fun onPinch(scale: Float, focus: ViewPoint) {
        if (!viewport.isReady) return
        val at = if (viewport.mode == InputMode.TOUCHPAD) pinchAt else focus
        viewport.setZoom(viewport.geometry.anchored(pinchZoom.zoom * scale, pinchAnchorX, pinchAnchorY, at.x, at.y))
    }

    override fun onPinchEnd() = Unit

    // ───────────────────────────── physical mouse ─────────────────────────────

    /** A mouse (USB, Bluetooth, DeX…) moved to ([x], [y]): the remote pointer follows exactly. */
    fun mouseMove(x: Float, y: Float, buttonsDown: Boolean) {
        val p = viewport.toRemote(x, y, clamp = buttonsDown) ?: return
        viewport.setCursor(p.x, p.y)
        if (canControl()) sender.move(p)
    }

    fun mouseButton(button: Int, down: Boolean, x: Float, y: Float) {
        if (!canControl()) return
        if (down) {
            val p = viewport.toRemote(x, y) ?: return
            viewport.setCursor(p.x, p.y)
            sender.press(button, p)
        } else if (button in sender.buttons) {
            sender.release(button, viewport.toRemote(x, y, clamp = true))
        }
    }

    /** Mouse wheel: [vertical] / [horizontal] notches (Android AXIS_VSCROLL > 0 = up, AXIS_HSCROLL > 0 = right). */
    fun mouseWheel(horizontal: Float, vertical: Float, x: Float, y: Float) {
        if (!canControl()) return
        val dx = (horizontal * WHEEL_NOTCH).roundToInt()
        val dy = (-vertical * WHEEL_NOTCH).roundToInt()
        sender.wheel(dx, dy, viewport.toRemote(x, y, clamp = true))
    }

    // ───────────────────────────── internals ─────────────────────────────

    private fun target(at: ViewPoint?, clamp: Boolean): RemotePoint? =
        if (at == null) viewport.cursor else viewport.toRemote(at.x, at.y, clamp)

    /** Fingers moved by ([dx], [dy]) view pixels: the content follows them (natural scrolling). */
    private fun scroll(dx: Float, dy: Float) {
        if (!canControl()) return
        val scale = viewport.pixelScale(remoteWidth()).takeIf { it > 0f } ?: 1f
        wheelX += -dx / scale * WHEEL_PER_PIXEL
        wheelY += -dy / scale * WHEEL_PER_PIXEL
        val ix = wheelX.toInt()
        val iy = wheelY.toInt()
        if (ix == 0 && iy == 0) return
        wheelX -= ix
        wheelY -= iy
        sender.wheel(ix, iy, scrollAt)
    }

    private fun startFling(vx0: Float, vy0: Float) {
        stopFling()
        var vx = vx0
        var vy = vy0
        var elapsed = 0L
        lateinit var tick: () -> Unit
        tick = {
            elapsed += FLING_FRAME_MS
            scroll(vx * FLING_FRAME_MS / 1000f, vy * FLING_FRAME_MS / 1000f)
            val decay = exp(-FLING_FRAME_MS / FLING_TIME_CONSTANT_MS).toFloat()
            vx *= decay
            vy *= decay
            fling = if (hypot(vx, vy) / density < STOP_FLING_DP_PER_S || elapsed >= MAX_FLING_MS || !canControl()) {
                null
            } else {
                scheduler.schedule(FLING_FRAME_MS, tick)
            }
        }
        fling = scheduler.schedule(FLING_FRAME_MS, tick)
    }

    fun stopFling() {
        fling?.cancel()
        fling = null
    }

    /** True while a drag holds a button down. */
    val isDragging: Boolean get() = dragButton != null

    /** The connection is gone or the gesture was cancelled: forget held buttons (no event sent). */
    fun forgetDrag() {
        dragButton = null
        dragModifiers = emptyList()
        stopFling()
    }

    fun dispose() {
        stopFling()
    }

    companion object {
        /** Wheel units per remote pixel: the desktop sends 1.2 × the browser's pixels (120 = one notch ≈ 100 px). */
        const val WHEEL_PER_PIXEL = 1.2

        /** One mouse wheel notch. */
        const val WHEEL_NOTCH = 120f

        const val FOLLOW_MARGIN_DP = 48f

        const val MIN_FLING_DP_PER_S = 350f
        const val STOP_FLING_DP_PER_S = 40f
        const val FLING_FRAME_MS = 16L
        const val FLING_TIME_CONSTANT_MS = 325.0
        const val MAX_FLING_MS = 2_500L
    }
}
