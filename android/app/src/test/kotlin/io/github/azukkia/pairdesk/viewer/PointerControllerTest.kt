package io.github.azukkia.pairdesk.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gestures → protocol events through the viewport (letterboxing, zoom, touchpad cursor). */
class PointerControllerTest {
    private val clock = ManualScheduler()
    private val sink = RecordingSink()
    private val sender = InputSender(sink, clock)
    private var control = true
    private val modifiers = ModifierKeys()
    private val keyboard = KeyboardController(sender, modifiers, layout = { RemoteLayout.QWERTY }, canControl = { control })

    /** A phone held in landscape (2400×1080) showing a 1920×1080 screen: 240 px bars left and right. */
    private val viewport = Viewport(InputMode.DIRECT).apply {
        setViewSize(2400, 1080)
        setFrameSize(1920, 1080)
    }
    private val pointer = PointerController(
        viewport = viewport,
        sender = sender,
        modifiers = keyboard,
        scheduler = clock,
        density = 1f,
        canControl = { control },
        remoteWidth = { 1920 },
    )

    @Test
    fun `a tap clicks at the remote point under the finger`() {
        pointer.onClick(MouseButton.LEFT, ViewPoint(240f + 480f, 270f))
        assertEquals(listOf("[[\"d\",0,0.25,0.25,1]]", "[[\"u\",0,0.25,0.25,2]]"), sink.reliable)
        // A tap in the black bars does nothing.
        pointer.onClick(MouseButton.LEFT, ViewPoint(100f, 270f))
        assertEquals(2, sink.reliable.size)
    }

    @Test
    fun `a tap on a zoomed picture maps through the zoom`() {
        viewport.setZoom(viewport.geometry.zoomAround(viewport.zoom, 1200f, 540f, 2f))
        pointer.onClick(MouseButton.RIGHT, ViewPoint(0f, 0f))
        assertEquals("[[\"d\",2,0.1875,0.25,1]]", sink.reliable.first())
    }

    @Test
    fun `direct hover follows the finger, drags keep going outside of the picture`() {
        pointer.onHover(1200f, 540f)
        assertEquals(listOf("[[\"m\",0.5,0.5,1,0]]"), sink.pointer)
        pointer.onHover(50f, 540f) // in the bar: ignored while no button is held
        assertEquals(1, sink.pointer.size)
        pointer.onButtonDown(MouseButton.LEFT, ViewPoint(1200f, 540f))
        pointer.onHover(50f, 540f)
        pointer.onButtonUp(MouseButton.LEFT, ViewPoint(10f, 540f))
        assertEquals("[[\"m\",0,0.5,3,2]]", sink.pointer.last())
        assertEquals("[[\"u\",0,0,0.5,4]]", sink.reliable.last())
        assertFalse(pointer.isDragging)
    }

    @Test
    fun `touchpad moves the cursor relatively, faster moves go further`() {
        viewport.mode = InputMode.TOUCHPAD
        viewport.setCursor(0.5, 0.5)
        // Slow: 10 px in 100 ms (0.1 px/ms) → gain 1 → 10 / 1920 of the width.
        pointer.onCursorMove(10f, 0f, 100)
        assertEquals(0.5 + 10.0 / 1920, viewport.cursorX, 1e-6)
        val slow = viewport.cursorX - 0.5
        // Fast: 60 px in 16 ms → accelerated.
        val before = viewport.cursorX
        pointer.onCursorMove(60f, 0f, 16)
        val fast = viewport.cursorX - before
        assertTrue(fast > slow * 6 * 1.5, "fast=$fast slow=$slow")
        assertEquals("[[\"m\",${RemotePoint.round4(viewport.cursorX)},0.5,2,0]]", sink.pointer.last())
        // The cursor stays on the screen.
        pointer.onCursorMove(100_000f, 100_000f, 16)
        assertEquals(1.0, viewport.cursorX)
        assertEquals(1.0, viewport.cursorY)
    }

    @Test
    fun `touchpad clicks happen at the cursor`() {
        viewport.mode = InputMode.TOUCHPAD
        viewport.setCursor(0.3, 0.6)
        pointer.onClick(MouseButton.LEFT, null)
        assertEquals(listOf("[[\"d\",0,0.3,0.6,1]]", "[[\"u\",0,0.3,0.6,2]]"), sink.reliable)
    }

    @Test
    fun `touchpad - a zoomed view follows the cursor`() {
        viewport.mode = InputMode.TOUCHPAD
        viewport.setZoom(viewport.geometry.zoomAround(viewport.zoom, 1200f, 540f, 3f))
        viewport.setCursor(0.5, 0.5)
        val left = viewport.placement()!!.left
        repeat(8) { pointer.onCursorMove(100f, 0f, 100) }
        assertTrue(viewport.cursorX > 0.75, "cursor ${viewport.cursorX}")
        val at = viewport.cursorInView()!!
        assertTrue(at.x <= 2400f - PointerController.FOLLOW_MARGIN_DP + 0.5f, "cursor at ${at.x}")
        assertTrue(viewport.placement()!!.left < left, "the picture moved left")
    }

    @Test
    fun `two fingers scroll in the natural direction, at the cursor in touchpad mode`() {
        viewport.mode = InputMode.TOUCHPAD
        viewport.setCursor(0.25, 0.75)
        // Fingers up by 100 px; 1 view px = 1 remote px here → 120 wheel units, content follows.
        pointer.onScroll(0f, -100f, ViewPoint(1200f, 540f))
        assertEquals(listOf("[[\"m\",0.25,0.75,1],[\"w\",0,120,1]]"), sink.reliable)
        sink.clear()
        // Fingers to the right: scroll left.
        pointer.onScroll(50f, 0f, ViewPoint(1200f, 540f))
        assertEquals(listOf("[[\"m\",0.25,0.75,2],[\"w\",-60,0,2]]"), sink.reliable)
    }

    @Test
    fun `direct scrolls happen where the fingers started, small moves add up`() {
        pointer.onScroll(0f, 0.5f, ViewPoint(1200f, 540f))
        assertTrue(sink.reliable.isEmpty())
        pointer.onScroll(0f, 0.5f, ViewPoint(1200f, 540f))
        assertEquals(listOf("[[\"m\",0.5,0.5,1],[\"w\",0,-1,1]]"), sink.reliable)
    }

    @Test
    fun `a fast scroll keeps going for a moment, a new touch stops it`() {
        pointer.onScroll(0f, -50f, ViewPoint(1200f, 540f))
        val afterScroll = sink.reliable.size
        pointer.onScrollEnd(0f, -3000f)
        clock.advance(100)
        val flung = sink.reliable.size
        assertTrue(flung > afterScroll + 3, "fling events: ${flung - afterScroll}")
        pointer.onTouchStart()
        clock.advance(1000)
        assertEquals(flung, sink.reliable.size)
        // And it ends by itself.
        pointer.onScrollEnd(0f, -3000f)
        clock.advance(10_000)
        val end = sink.reliable.size
        clock.advance(1000)
        assertEquals(end, sink.reliable.size)
    }

    @Test
    fun `a pinch zooms around the fingers`() {
        pointer.onPinchStart(ViewPoint(1200f, 540f))
        pointer.onPinch(2f, ViewPoint(1200f, 540f))
        assertEquals(2f, viewport.zoom.zoom)
        assertEquals(0.5, viewport.toRemote(1200f, 540f)!!.x)
        pointer.onPinch(10f, ViewPoint(1200f, 540f))
        assertEquals(ViewZoom.MAX, viewport.zoom.zoom)
        pointer.onPinchEnd()
        assertTrue(sink.all.isEmpty(), "zooming sends nothing")
    }

    @Test
    fun `panning moves the zoomed picture`() {
        viewport.setZoom(viewport.geometry.zoomAround(viewport.zoom, 1200f, 540f, 2f))
        val before = viewport.toRemote(1200f, 540f)!!
        pointer.onPan(-100f, 0f)
        val after = viewport.toRemote(1200f, 540f)!!
        assertTrue(after.x > before.x)
    }

    @Test
    fun `sticky modifiers are held around a click`() {
        modifiers.tap(StickyModifier.CTRL)
        pointer.onClick(MouseButton.LEFT, ViewPoint(1200f, 540f))
        assertEquals(
            listOf(
                "[[\"k\",\"ControlLeft\",1]]",
                "[[\"d\",0,0.5,0.5,1]]",
                "[[\"u\",0,0.5,0.5,2]]",
                "[[\"k\",\"ControlLeft\",0]]",
            ),
            sink.reliable,
        )
        assertEquals(StickyState.OFF, modifiers.state(StickyModifier.CTRL))
    }

    @Test
    fun `and around a drag`() {
        modifiers.lock(StickyModifier.SHIFT)
        pointer.onButtonDown(MouseButton.LEFT, ViewPoint(1200f, 540f))
        pointer.onHover(1300f, 540f)
        pointer.onButtonUp(MouseButton.LEFT, ViewPoint(1300f, 540f))
        assertEquals("[[\"k\",\"ShiftLeft\",1]]", sink.reliable.first())
        assertEquals("[[\"k\",\"ShiftLeft\",0]]", sink.reliable.last())
        assertEquals(StickyState.LOCKED, modifiers.state(StickyModifier.SHIFT))
    }

    @Test
    fun `nothing is sent without control, the view still zooms`() {
        control = false
        pointer.onClick(MouseButton.LEFT, ViewPoint(1200f, 540f))
        pointer.onHover(1200f, 540f)
        pointer.onScroll(0f, -100f, ViewPoint(1200f, 540f))
        pointer.onButtonDown(MouseButton.LEFT, ViewPoint(1200f, 540f))
        pointer.onPinchStart(ViewPoint(1200f, 540f))
        pointer.onPinch(2f, ViewPoint(1200f, 540f))
        assertTrue(sink.all.isEmpty())
        assertEquals(2f, viewport.zoom.zoom)
    }

    @Test
    fun `a physical mouse maps exactly, its wheel goes by notches`() {
        pointer.mouseMove(1200f, 540f, buttonsDown = false)
        pointer.mouseButton(MouseButton.RIGHT, true, 1200f, 540f)
        pointer.mouseButton(MouseButton.RIGHT, false, 1200f, 540f)
        pointer.mouseWheel(0f, -1f, 1200f, 540f) // one notch down
        assertEquals("[[\"m\",0.5,0.5,1,0]]", sink.pointer.single())
        assertEquals(
            listOf("[[\"d\",2,0.5,0.5,2]]", "[[\"u\",2,0.5,0.5,3]]", "[[\"m\",0.5,0.5,4],[\"w\",0,120,4]]"),
            sink.reliable,
        )
    }

    @Test
    fun `touchpad acceleration is smooth and bounded`() {
        val a = TouchpadAcceleration()
        assertEquals(1f, a.gain(0f))
        assertEquals(1f, a.gain(a.slowDpPerMs))
        assertEquals(3f, a.gain(10f))
        var last = 0f
        for (i in 0..40) {
            val g = a.gain(i * 0.05f)
            assertTrue(g >= last)
            last = g
        }
    }
}
