package io.github.azukkia.pairdesk.viewer

import io.github.azukkia.pairdesk.core.json.JsonJs
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Event formats of docs/PROTOCOL.md 4.1 and the sequence numbering of
 * src/renderer/viewer/viewer.js (sendMove / sendReliable / mouse handlers).
 */
class InputSenderTest {
    private val sink = RecordingSink()
    private val clock = ManualScheduler()
    private val sender = InputSender(sink, clock)

    private fun json(events: JsonArray) = JsonJs.stringify(events)

    @Test
    fun `numbers are written like JSON stringify`() {
        assertEquals("0.5", InputEvents.coord(0.5).toString())
        assertEquals("1", InputEvents.coord(1.0).toString())
        assertEquals("0", InputEvents.coord(0.0).toString())
        assertEquals("0.1", InputEvents.coord(0.1).toString())
        assertEquals("0.0001", InputEvents.coord(0.00005).toString())
        assertEquals("0.1235", InputEvents.coord(0.12346).toString())
        assertEquals("[\"m\",0.25,0.75,3,1]", json(InputEvents.move(RemotePoint(0.25, 0.75), 3, 1)))
        assertEquals("[\"d\",2,0.5,1,7]", json(InputEvents.down(MouseButton.RIGHT, RemotePoint(0.5, 1.0), 7)))
        assertEquals("[\"u\",0]", json(InputEvents.up(MouseButton.LEFT)))
        assertEquals("[\"w\",-12,240,4]", json(InputEvents.wheel(-12, 240, 4)))
        assertEquals("[\"k\",\"KeyA\",1]", json(InputEvents.key("KeyA", true)))
        assertEquals("[\"t\",\"é\"]", json(InputEvents.text("é")))
        assertEquals("[\"r\"]", json(InputEvents.releaseAll()))
        assertEquals("[\"a\",\"back\"]", json(InputEvents.action("back")))
    }

    @Test
    fun `moves go on the pointer channel, the resting position again on the reliable one`() {
        sender.move(RemotePoint(0.5, 0.25))
        assertEquals(listOf("[[\"m\",0.5,0.25,1,0]]"), sink.pointer)
        assertTrue(sink.reliable.isEmpty())
        clock.advance(119)
        assertTrue(sink.reliable.isEmpty())
        clock.advance(1)
        // Fresh number, no btnSeq: it queues behind any late click.
        assertEquals(listOf("[[\"m\",0.5,0.25,2]]"), sink.reliable)
        assertEquals(2, sender.seq)
    }

    @Test
    fun `the settle timer restarts with every move and a repeated position is not sent`() {
        sender.move(RemotePoint(0.1, 0.1))
        clock.advance(100)
        sender.move(RemotePoint(0.2, 0.2))
        sender.move(RemotePoint(0.2, 0.2))
        clock.advance(100)
        assertTrue(sink.reliable.isEmpty())
        clock.advance(20)
        assertEquals(listOf("[[\"m\",0.1,0.1,1,0]]", "[[\"m\",0.2,0.2,2,0]]"), sink.pointer)
        assertEquals(listOf("[[\"m\",0.2,0.2,3]]"), sink.reliable)
    }

    @Test
    fun `clicks are numbered and become the btnSeq of the following moves`() {
        sender.move(RemotePoint(0.3, 0.3))
        sender.press(MouseButton.LEFT, RemotePoint(0.3, 0.3))
        sender.move(RemotePoint(0.4, 0.4))
        sender.release(MouseButton.LEFT, RemotePoint(0.4, 0.4))
        sender.move(RemotePoint(0.5, 0.5))
        assertEquals(
            listOf(
                "P [[\"m\",0.3,0.3,1,0]]",
                "R [[\"d\",0,0.3,0.3,2]]",
                "P [[\"m\",0.4,0.4,3,2]]",
                "R [[\"u\",0,0.4,0.4,4]]",
                "P [[\"m\",0.5,0.5,5,4]]",
            ),
            sink.all,
        )
        assertTrue(sender.buttons.isEmpty())
    }

    @Test
    fun `a release outside of the picture has no position`() {
        sender.press(MouseButton.RIGHT, RemotePoint(0.5, 0.5))
        sender.release(MouseButton.RIGHT, null)
        assertEquals(listOf("[[\"d\",2,0.5,0.5,1]]", "[[\"u\",2]]"), sink.reliable)
    }

    @Test
    fun `a wheel event is preceded by a reliable move with the same number`() {
        sender.move(RemotePoint(0.1, 0.1))
        sender.wheel(0, 120, RemotePoint(0.6, 0.7))
        assertEquals(listOf("[[\"m\",0.6,0.7,2],[\"w\",0,120,2]]"), sink.reliable)
        // The pending settle of the earlier move is cancelled.
        clock.advance(500)
        assertEquals(1, sink.reliable.size)
        // The next move carries the wheel's number as btnSeq.
        sender.move(RemotePoint(0.61, 0.7))
        assertEquals("[[\"m\",0.61,0.7,3,2]]", sink.pointer.last())
        // Without a position (outside the picture): no numbers; zero is not sent.
        sender.wheel(-30, 0, null)
        sender.wheel(0, 0, RemotePoint(0.5, 0.5))
        assertEquals("[[\"w\",-30,0]]", sink.reliable.last())
        assertEquals(2, sink.reliable.size)
    }

    @Test
    fun `wheel deltas are clamped like the host does`() {
        sender.wheel(99_999, -99_999, null)
        assertEquals("[[\"w\",2400,-2400]]", sink.reliable.single())
    }

    @Test
    fun `combinations go down in order and up in reverse order in one message`() {
        sender.combo(KeyCombo.CTRL_ALT_DEL.codes)
        assertEquals(
            "[[\"k\",\"ControlLeft\",1],[\"k\",\"AltLeft\",1],[\"k\",\"Delete\",1],[\"k\",\"Delete\",0],[\"k\",\"AltLeft\",0],[\"k\",\"ControlLeft\",0]]",
            sink.reliable.single(),
        )
        assertTrue(sender.keys.isEmpty())
    }

    @Test
    fun `releaseAll is only sent when something is held`() {
        sender.releaseAll()
        assertTrue(sink.reliable.isEmpty())
        sender.key("ShiftLeft", true)
        sender.releaseAll()
        assertEquals(listOf("[[\"k\",\"ShiftLeft\",1]]", "[[\"r\"]]"), sink.reliable)
        sender.releaseAll()
        assertEquals(2, sink.reliable.size)
    }

    @Test
    fun `text is cut in pieces of at most 256 characters, never inside a surrogate pair`() {
        val long = "a".repeat(300)
        sender.text(long)
        val sent = JsonJs.parse(sink.reliable.single()) as JsonArray
        assertEquals(2, sent.size)
        assertEquals(256, ((sent[0] as JsonArray)[1]).toString().length - 2)
        val emoji = "a".repeat(255) + "😀b"
        val pieces = InputEvents.chunkText(emoji)
        assertEquals("a".repeat(255), pieces[0])
        assertEquals("😀b", pieces[1])
        sender.text("")
        assertEquals(1, sink.reliable.size)
    }

    @Test
    fun `sequence numbers keep increasing across reconnections`() {
        sender.move(RemotePoint(0.1, 0.1))
        sender.press(MouseButton.LEFT, RemotePoint(0.1, 0.1))
        sender.resetForNewConnection()
        sink.clear()
        sender.move(RemotePoint(0.1, 0.1))
        // Same position, but the last move was forgotten: sent, with btnSeq reset to 0.
        assertEquals(listOf("[[\"m\",0.1,0.1,3,0]]"), sink.pointer)
        clock.advance(200)
        assertEquals(listOf("[[\"m\",0.1,0.1,4]]"), sink.reliable)
    }
}
