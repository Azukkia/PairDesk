package io.github.azukkia.pairdesk.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureEngineTest {
    private val clock = ManualScheduler()
    private val calls = ArrayList<String>()
    private var zoomed = false
    private val config = GestureConfig(touchSlop = 10f, doubleTapSlop = 50f)

    private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
    private fun pt(p: ViewPoint?) = if (p == null) "cursor" else "${fmt(p.x)},${fmt(p.y)}"

    private val listener = object : GestureListener {
        override fun onTouchStart() {
            calls += "start"
        }

        override fun onLongPress() {
            calls += "long"
        }

        override fun onHover(x: Float, y: Float) {
            calls += "hover ${fmt(x)},${fmt(y)}"
        }

        override fun onCursorMove(dx: Float, dy: Float, dtMs: Long) {
            calls += "cursor ${fmt(dx)},${fmt(dy)}"
        }

        override fun onClick(button: Int, at: ViewPoint?) {
            calls += "click $button ${pt(at)}"
        }

        override fun onButtonDown(button: Int, at: ViewPoint?) {
            calls += "down $button ${pt(at)}"
        }

        override fun onButtonUp(button: Int, at: ViewPoint?) {
            calls += "up $button ${pt(at)}"
        }

        override fun onPan(dx: Float, dy: Float) {
            calls += "pan ${fmt(dx)},${fmt(dy)}"
        }

        override fun onScroll(dx: Float, dy: Float, focus: ViewPoint) {
            calls += "scroll ${fmt(dx)},${fmt(dy)} @${pt(focus)}"
        }

        override fun onScrollEnd(vx: Float, vy: Float) {
            calls += "scrollEnd ${if (vx == 0f && vy == 0f) "still" else "fling"}"
        }

        override fun onPinchStart(focus: ViewPoint) {
            calls += "pinchStart ${pt(focus)}"
        }

        override fun onPinch(scale: Float, focus: ViewPoint) {
            calls += "pinch ${"%.2f".format(java.util.Locale.ROOT, scale)}"
        }

        override fun onPinchEnd() {
            calls += "pinchEnd"
        }
    }

    private val engine = GestureEngine(config, clock, listener, isZoomed = { zoomed })

    // ── helpers: one or two fingers, time driven by the manual clock ──

    private fun ev(action: TouchAction, id: Int, vararg p: TouchPointer) = engine.onTouch(TouchEvent(action, p.toList(), id, clock.now))

    private fun down(x: Float, y: Float) = ev(TouchAction.DOWN, 0, TouchPointer(0, x, y))
    private fun move(x: Float, y: Float) = ev(TouchAction.MOVE, 0, TouchPointer(0, x, y))
    private fun up(x: Float, y: Float) = ev(TouchAction.UP, 0, TouchPointer(0, x, y))

    private fun tap(x: Float, y: Float, holdMs: Long = 60) {
        down(x, y)
        clock.advance(holdMs)
        up(x, y)
    }

    /** Two fingers from (a, b) moving by (dx, dy) each, plus [spread] apart horizontally, in [steps]. */
    private fun twoFingers(ax: Float, ay: Float, bx: Float, by: Float, dx: Float, dy: Float, spread: Float = 0f, steps: Int = 4, liftMs: Long = 16) {
        ev(TouchAction.DOWN, 0, TouchPointer(0, ax, ay))
        ev(TouchAction.POINTER_DOWN, 1, TouchPointer(0, ax, ay), TouchPointer(1, bx, by))
        for (i in 1..steps) {
            clock.advance(16)
            val f = i.toFloat() / steps
            ev(
                TouchAction.MOVE,
                0,
                TouchPointer(0, ax + dx * f - spread * f / 2, ay + dy * f),
                TouchPointer(1, bx + dx * f + spread * f / 2, by + dy * f),
            )
        }
        clock.advance(liftMs)
        val a = TouchPointer(0, ax + dx - spread / 2, ay + dy)
        val b = TouchPointer(1, bx + dx + spread / 2, by + dy)
        ev(TouchAction.POINTER_UP, 1, a, b)
        ev(TouchAction.UP, 0, a)
    }

    // ───────────────────────────── direct touch ─────────────────────────────

    @Test
    fun `direct - a tap clicks where the finger is`() {
        engine.mode = InputMode.DIRECT
        tap(100f, 200f)
        assertEquals(listOf("start", "click 0 100,200"), calls)
    }

    @Test
    fun `direct - the second tap of a double tap lands exactly on the first one`() {
        engine.mode = InputMode.DIRECT
        tap(100f, 200f)
        clock.advance(150)
        tap(112f, 190f)
        clock.advance(150)
        tap(112f, 190f)
        assertEquals(listOf("start", "click 0 100,200", "start", "click 0 100,200", "start", "click 0 112,190"), calls)
    }

    @Test
    fun `direct - a slow second tap or a far one is a new click`() {
        engine.mode = InputMode.DIRECT
        tap(100f, 200f)
        clock.advance(600)
        tap(105f, 200f)
        tap(400f, 200f)
        assertEquals(listOf("start", "click 0 100,200", "start", "click 0 105,200", "start", "click 0 400,200"), calls)
    }

    @Test
    fun `direct - a long press is a right click where the finger rests`() {
        engine.mode = InputMode.DIRECT
        down(300f, 300f)
        clock.advance(500)
        move(304f, 302f) // within the slop
        up(304f, 302f)
        assertEquals(listOf("start", "long", "click 2 300,300"), calls)
    }

    @Test
    fun `direct - long press then drag holds the left button`() {
        engine.mode = InputMode.DIRECT
        down(300f, 300f)
        clock.advance(500)
        move(340f, 300f)
        move(380f, 320f)
        up(390f, 330f)
        assertEquals(
            listOf("start", "long", "down 0 300,300", "hover 340,300", "hover 380,320", "up 0 390,330"),
            calls,
        )
    }

    @Test
    fun `direct - one finger moving hovers, or pans when zoomed`() {
        engine.mode = InputMode.DIRECT
        down(100f, 100f)
        move(105f, 100f) // slop
        move(130f, 100f)
        move(160f, 110f)
        up(160f, 110f)
        assertEquals(listOf("start", "hover 130,100", "hover 160,110"), calls)

        calls.clear()
        zoomed = true
        down(100f, 100f)
        move(130f, 100f)
        move(150f, 90f)
        up(150f, 90f)
        assertEquals(listOf("start", "pan 30,0", "pan 20,-10"), calls)
    }

    @Test
    fun `two fingers moving together scroll from where they started`() {
        engine.mode = InputMode.DIRECT
        twoFingers(100f, 500f, 200f, 500f, dx = 0f, dy = -200f)
        assertEquals("start", calls.first())
        val scrolls = calls.filter { it.startsWith("scroll ") }
        assertTrue(scrolls.isNotEmpty(), calls.toString())
        assertTrue(scrolls.all { it.endsWith("@150,500") }, scrolls.toString())
        val total = scrolls.sumOf { it.removePrefix("scroll ").substringBefore(" @").split(",")[1].toDouble() }
        assertEquals(-200.0, total, 0.01)
        assertEquals("scrollEnd fling", calls.last())
        assertTrue(calls.none { it.startsWith("click") || it.startsWith("pinch") })
    }

    @Test
    fun `fingers resting before lifting do not fling`() {
        twoFingers(100f, 500f, 200f, 500f, dx = 0f, dy = -200f, liftMs = 200)
        assertEquals("scrollEnd still", calls.last())
    }

    @Test
    fun `two fingers moving apart pinch`() {
        twoFingers(400f, 500f, 600f, 500f, dx = 0f, dy = 0f, spread = 200f)
        assertEquals("pinchStart 500,500", calls[1])
        assertEquals("pinch 2.00", calls.last { it.startsWith("pinch ") })
        assertEquals("pinchEnd", calls.last())
        assertTrue(calls.none { it.startsWith("scroll") || it.startsWith("click") })
    }

    @Test
    fun `a two-finger tap is a right click, three fingers a middle click`() {
        engine.mode = InputMode.DIRECT
        twoFingers(100f, 100f, 160f, 100f, dx = 0f, dy = 0f, steps = 1)
        assertEquals(listOf("start", "click 2 130,100"), calls)

        calls.clear()
        engine.mode = InputMode.TOUCHPAD
        ev(TouchAction.DOWN, 0, TouchPointer(0, 100f, 100f))
        ev(TouchAction.POINTER_DOWN, 1, TouchPointer(0, 100f, 100f), TouchPointer(1, 150f, 100f))
        ev(TouchAction.POINTER_DOWN, 2, TouchPointer(0, 100f, 100f), TouchPointer(1, 150f, 100f), TouchPointer(2, 200f, 100f))
        clock.advance(80)
        ev(TouchAction.POINTER_UP, 2, TouchPointer(0, 100f, 100f), TouchPointer(1, 150f, 100f), TouchPointer(2, 200f, 100f))
        ev(TouchAction.POINTER_UP, 1, TouchPointer(0, 100f, 100f), TouchPointer(1, 150f, 100f))
        ev(TouchAction.UP, 0, TouchPointer(0, 100f, 100f))
        assertEquals(listOf("start", "click 1 cursor"), calls)
    }

    @Test
    fun `a long two-finger touch is not a click`() {
        twoFingers(100f, 100f, 160f, 100f, dx = 0f, dy = 0f, steps = 1, liftMs = 600)
        assertEquals(listOf("start"), calls)
    }

    // ───────────────────────────── touchpad ─────────────────────────────

    @Test
    fun `touchpad - one finger moves the cursor relatively`() {
        engine.mode = InputMode.TOUCHPAD
        down(100f, 100f)
        clock.advance(16)
        move(115f, 100f)
        clock.advance(16)
        move(125f, 95f)
        up(125f, 95f)
        clock.advance(1000)
        assertEquals(listOf("start", "cursor 15,0", "cursor 10,-5"), calls)
    }

    @Test
    fun `touchpad - a tap clicks at the cursor once the double-tap delay passed`() {
        engine.mode = InputMode.TOUCHPAD
        tap(100f, 100f)
        assertEquals(listOf("start"), calls)
        clock.advance(config.doubleTapMs)
        assertEquals(listOf("start", "click 0 cursor"), calls)
    }

    @Test
    fun `touchpad - a resting finger is not a click`() {
        engine.mode = InputMode.TOUCHPAD
        tap(100f, 100f, holdMs = 400)
        clock.advance(1000)
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `touchpad - tap tap is a double click`() {
        engine.mode = InputMode.TOUCHPAD
        tap(100f, 100f)
        clock.advance(100)
        tap(300f, 300f) // anywhere on the pad
        clock.advance(1000)
        assertEquals(listOf("start", "start", "click 0 cursor", "click 0 cursor"), calls)
    }

    @Test
    fun `touchpad - tap then touch and move drags with the left button`() {
        engine.mode = InputMode.TOUCHPAD
        tap(100f, 100f)
        clock.advance(100)
        down(100f, 100f)
        clock.advance(50)
        move(130f, 100f)
        clock.advance(16)
        move(150f, 120f)
        up(150f, 120f)
        clock.advance(1000)
        assertEquals(listOf("start", "start", "down 0 cursor", "cursor 30,0", "cursor 20,20", "up 0 cursor"), calls)
    }

    @Test
    fun `touchpad - tap then touch and hold starts the drag before any move`() {
        engine.mode = InputMode.TOUCHPAD
        tap(100f, 100f)
        clock.advance(100)
        down(100f, 100f)
        clock.advance(config.dragHoldMs)
        assertEquals(listOf("start", "start", "down 0 cursor"), calls)
        up(100f, 100f)
        assertEquals("up 0 cursor", calls.last())
    }

    @Test
    fun `touchpad - two-finger tap right-clicks at the cursor`() {
        engine.mode = InputMode.TOUCHPAD
        twoFingers(100f, 100f, 160f, 100f, dx = 0f, dy = 0f, steps = 1)
        assertEquals(listOf("start", "click 2 cursor"), calls)
    }

    @Test
    fun `switching mode ends a drag in progress`() {
        engine.mode = InputMode.DIRECT
        down(300f, 300f)
        clock.advance(500)
        move(360f, 300f)
        engine.mode = InputMode.TOUCHPAD
        assertEquals("up 0 360,300", calls.last())
        move(400f, 300f)
        up(400f, 300f)
        assertEquals("up 0 360,300", calls.last())
    }

    @Test
    fun `a cancelled gesture releases the button`() {
        engine.mode = InputMode.DIRECT
        down(300f, 300f)
        clock.advance(500)
        move(360f, 300f)
        ev(TouchAction.CANCEL, 0, TouchPointer(0, 360f, 300f))
        assertEquals("up 0 360,300", calls.last())
    }

    @Test
    fun `velocity tracker measures the last 100 ms only`() {
        val v = VelocityTracker()
        v.add(0f, 0f, 0)
        v.add(0f, 0f, 500)
        v.add(0f, 100f, 550)
        v.add(0f, 200f, 600)
        val (vx, vy) = v.velocity(610)
        assertEquals(0f, vx)
        assertEquals(2000f, vy, 1f)
        assertEquals(0f to 0f, v.velocity(800))
    }
}
