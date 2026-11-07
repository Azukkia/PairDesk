package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.viewer.ManualScheduler
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Input events of a desktop viewer → gestures, navigation and edits of the phone. */
class HostInputTest {
    private val clock = ManualScheduler()

    private class FakeInjector : InputInjector {
        val strokes = ArrayList<Stroke>()
        val pending = ArrayList<() -> Unit>()
        val nav = ArrayList<NavAction>()
        val edits = ArrayList<EditCommand>()
        var accept = true

        override fun dispatch(stroke: Stroke, onDone: () -> Unit): Boolean {
            if (!accept) return false
            strokes += stroke
            pending += onDone
            return true
        }

        override fun navigate(action: NavAction): Boolean {
            nav += action
            return true
        }

        override fun edit(command: EditCommand): Boolean {
            edits += command
            return true
        }

        /** The gesture being injected ends. */
        fun complete() = pending.removeAt(0)()
    }

    private val injector = FakeInjector()
    private var service: InputInjector? = injector
    private val input = HostInput(
        injector = { service },
        scheduler = clock,
        clock = { clock.now },
        config = HostInputConfig(touchSlop = 20f, longPressMs = 500, wheelScale = 2f),
    ).apply {
        setScreenSize(1081, 2401) // normalized 0.5 → exactly 540, 1200
        enabled = true
    }

    private fun send(json: String) = input.onEvents(JsonJs.parse(json) as JsonArray)

    private fun p(x: Float, y: Float) = ScreenPoint(x, y)

    @Test
    fun `a click is a tap where it was made`() {
        send("""[["m",0.5,0.5,1,0]]""")
        send("""[["d",0,0.5,0.5,2]]""")
        clock.advance(120)
        send("""[["u",0,0.5,0.5,3]]""")
        assertEquals(listOf(Stroke(listOf(p(540f, 1200f)), HostInput.TAP_MS)), injector.strokes)
    }

    @Test
    fun `a long click is a long press of the same length`() {
        send("""[["d",0,0.25,0.75,1]]""")
        clock.advance(900)
        send("""[["u",0,0.25,0.75,2]]""")
        assertEquals(listOf(Stroke(listOf(p(270f, 1800f)), 900)), injector.strokes)
    }

    @Test
    fun `a drag is a swipe along the moves, at the same speed`() {
        send("""[["d",0,0.1,0.5,1]]""")
        clock.advance(50)
        send("""[["m",0.3,0.5,2,1]]""")
        clock.advance(50)
        send("""[["m",0.3,0.5,3,1],["m",0.5,0.5,4,1]]""")
        clock.advance(50)
        assertTrue(injector.strokes.isEmpty(), "injected when the button goes up")
        send("""[["u",0,0.6,0.5,5]]""")
        assertEquals(1, injector.strokes.size)
        val s = injector.strokes[0]
        assertEquals(listOf(p(108f, 1200f), p(324f, 1200f), p(540f, 1200f), p(648f, 1200f)), s.points)
        assertEquals(150L, s.durationMs)
    }

    @Test
    fun `a small trembling during a click is still a tap`() {
        send("""[["d",0,0.5,0.5,1]]""")
        send("""[["m",0.505,0.5,2,1]]""") // 5 px, under half the slop
        send("""[["u",0,0.505,0.5,3]]""")
        assertEquals(listOf(Stroke(listOf(p(540f, 1200f)), HostInput.TAP_MS)), injector.strokes)
    }

    @Test
    fun `gestures wait for the previous one (Android would cancel it)`() {
        send("""[["d",0,0.1,0.1,1]]""")
        send("""[["u",0,0.1,0.1,2]]""")
        send("""[["d",0,0.2,0.2,3]]""")
        send("""[["u",0,0.2,0.2,4]]""")
        assertEquals(1, injector.strokes.size)
        assertEquals(1, input.queued)
        injector.complete()
        assertEquals(2, injector.strokes.size)
        assertEquals(p(216f, 480f), injector.strokes[1].points[0])
        injector.complete()
        assertFalse(input.busy)
    }

    @Test
    fun `a gesture the service never reports done does not block the queue`() {
        send("""[["d",0,0.1,0.1,1]]""")
        send("""[["u",0,0.1,0.1,2]]""")
        send("""[["d",0,0.2,0.2,3]]""")
        send("""[["u",0,0.2,0.2,4]]""")
        clock.advance(HostInput.TAP_MS + HostInput.WATCHDOG_MS)
        assertEquals(2, injector.strokes.size)
        // A late callback of the first gesture does not release the second one.
        injector.complete()
        assertTrue(input.busy)
    }

    @Test
    fun `right click is a long press, back button is Back`() {
        send("""[["m",0.5,0.25,1]]""")
        send("""[["d",2,0.5,0.25,2]]""")
        send("""[["u",2,0.5,0.25,3]]""")
        assertEquals(listOf(Stroke(listOf(p(540f, 600f)), 500 + HostInput.LONG_PRESS_MARGIN_MS)), injector.strokes)
        send("""[["d",3,0.5,0.25,4]]""")
        assertEquals(listOf(NavAction.BACK), injector.nav)
    }

    @Test
    fun `wheel scrolls with a swipe the other way, from the pointer`() {
        send("""[["m",0.5,0.5,1],["w",0,120,1]]""")
        // Scrolling down: the finger moves up by 120 × 2 px plus the touch slop.
        assertEquals(listOf(Stroke(listOf(p(540f, 1200f), p(540f, 940f)), HostInput.WHEEL_SWIPE_MS)), injector.strokes)
        // Notches arriving meanwhile are added up into the next swipe.
        send("""[["w",0,-120,2]]""")
        send("""[["w",0,-120,3]]""")
        assertEquals(1, injector.strokes.size)
        injector.complete()
        assertEquals(listOf(p(540f, 1200f), p(540f, 1700f)), injector.strokes[1].points)
        injector.complete()
        assertFalse(input.busy)
    }

    @Test
    fun `wheel near an edge starts where there is room`() {
        send("""[["m",0.5,0.01,1],["w",0,240,1]]""")
        val s = injector.strokes.single()
        assertEquals(500f, s.points[0].y) // 480 + 20 of slop above the end point at 0
        assertEquals(0f, s.points[1].y)
    }

    @Test
    fun `a large wheel is split into swipes of at most 45 percent of the screen`() {
        send("""[["m",0.5,0.5,1],["w",0,2400,1]]""")
        val first = injector.strokes.single()
        assertEquals(1200f - (2401 * HostInput.MAX_SWIPE_FRACTION).toFloat() - 20f, first.points[1].y, 0.01f)
        injector.complete()
        assertEquals(2, injector.strokes.size, "the rest follows")
    }

    @Test
    fun `a wheel waiting for a gesture goes before the next click`() {
        send("""[["d",0,0.5,0.5,1]]""")
        send("""[["u",0,0.5,0.5,2]]""")
        send("""[["m",0.5,0.5,3],["w",0,60,3]]""")
        send("""[["d",0,0.1,0.1,4]]""")
        send("""[["u",0,0.1,0.1,5]]""")
        injector.complete()
        assertEquals(2, injector.strokes[1].points.size, "the wheel swipe")
        injector.complete()
        assertEquals(listOf(p(108f, 240f)), injector.strokes[2].points)
    }

    @Test
    fun `moves that overtook a click are dropped`() {
        send("""[["d",0,0.1,0.1,5]]""")
        send("""[["m",0.9,0.9,4]]""") // older than the press
        send("""[["m",0.5,0.5,7,6]]""") // made after a release not applied yet
        send("""[["u",0,0.1,0.1,6]]""")
        assertEquals(listOf(p(108f, 240f)), injector.strokes.single().points)
    }

    @Test
    fun `keys edit the focused field, Escape is Back`() {
        send("""[["k","Enter",1],["k","Enter",0],["k","Backspace",1],["k","Delete",1],["k","Tab",1]]""")
        send("""[["k","ShiftLeft",1],["k","ArrowLeft",1],["k","ArrowLeft",0],["k","ShiftLeft",0],["k","End",1]]""")
        send("""[["k","ControlLeft",1],["k","KeyA",1],["k","KeyC",1],["k","KeyX",1],["k","KeyV",1],["k","ControlLeft",0]]""")
        send("""[["k","MetaLeft",1],["k","KeyV",1],["k","MetaLeft",0],["k","KeyV",1],["k","Escape",1]]""")
        assertEquals(
            listOf(
                EditCommand.Enter, EditCommand.Backspace, EditCommand.Delete, EditCommand.Tab,
                EditCommand.Move(CaretMove.LEFT, extend = true), EditCommand.Move(CaretMove.END, extend = false),
                EditCommand.SelectAll, EditCommand.Copy, EditCommand.Cut, EditCommand.Paste,
                EditCommand.Paste,
            ),
            injector.edits,
        )
        assertEquals(listOf(NavAction.BACK), injector.nav)
    }

    @Test
    fun `text and navigation actions`() {
        send("""[["t","Bonjour é😀"],["t",""],["t",5]]""")
        send("""[["a","home"],["a","recents"],["a","notifications"],["a","back"],["a","nope"]]""")
        assertEquals(listOf(EditCommand.Type("Bonjour é😀")), injector.edits)
        assertEquals(listOf(NavAction.HOME, NavAction.RECENTS, NavAction.NOTIFICATIONS, NavAction.BACK), injector.nav)
        send("""[["t","${"x".repeat(300)}"]]""")
        assertEquals(EditCommand.Type("x".repeat(256)), injector.edits.last())
    }

    @Test
    fun `release all ends a held drag and the modifiers`() {
        send("""[["k","ControlLeft",1]]""")
        send("""[["d",0,0.5,0.5,1]]""")
        send("""[["r"]]""")
        assertEquals(1, injector.strokes.size)
        send("""[["k","KeyV",1]]""")
        assertTrue(injector.edits.isEmpty(), "Ctrl was released")
    }

    @Test
    fun `view-only sessions inject nothing but keep counting clicks`() {
        input.enabled = false
        send("""[["d",0,0.5,0.5,10]]""")
        send("""[["u",0,0.5,0.5,11]]""")
        send("""[["t","x"],["a","home"],["w",0,100]]""")
        assertTrue(injector.strokes.isEmpty() && injector.edits.isEmpty() && injector.nav.isEmpty())
        input.enabled = true
        send("""[["m",0.2,0.2,9,0]]""") // older than the clicks counted meanwhile
        send("""[["d",0,0.3,0.3,12]]""")
        send("""[["u",0,0.3,0.3,13]]""")
        assertEquals(listOf(p(324f, 720f)), injector.strokes.single().points)
    }

    @Test
    fun `without the accessibility service nothing is queued`() {
        service = null
        send("""[["d",0,0.5,0.5,1]]""")
        send("""[["u",0,0.5,0.5,2]]""")
        assertEquals(0, input.queued)
        assertFalse(input.busy)
    }

    @Test
    fun `coordinates are clamped to the screen and follow its rotation`() {
        send("""[["d",0,-1,2,1]]""")
        send("""[["u",0,-1,2,2]]""")
        assertEquals(listOf(p(0f, 2400f)), injector.strokes.single().points)
        injector.complete()
        input.setScreenSize(2401, 1081)
        send("""[["d",0,1,1,3]]""")
        send("""[["u",0,1,1,4]]""")
        assertEquals(listOf(p(2400f, 1080f)), injector.strokes[1].points)
    }

    @Test
    fun `malformed events are ignored`() {
        send("""[5,["m"],["d","left",0.5,0.5],["k",1,1],["k","${"K".repeat(40)}",1],["w","a","b"],{"m":1}]""")
        assertTrue(injector.strokes.isEmpty() && injector.edits.isEmpty())
    }
}
