package io.github.azukkia.pairdesk.viewer

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.rtc.ReceiverStats
import io.github.azukkia.pairdesk.rtc.RtcState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The viewer logic of src/renderer/viewer/viewer.js, on fake data channels. */
class ViewerCoreTest {
    private val clock = ManualScheduler()

    private class FakeLink : ViewerLink {
        val control = ArrayList<String>()
        val input = ArrayList<String>()
        val pointer = ArrayList<String>()
        val clipboard = ArrayList<String>()
        val ended = ArrayList<String>()
        var controlOpen = true
        var savedQuality: Quality? = null
        var savedSync: Boolean? = null

        override fun sendControl(message: JsonObject): Boolean {
            if (!controlOpen) return false
            control += JsonJs.stringify(message)
            return true
        }

        override fun sendInput(events: JsonArray) {
            input += JsonJs.stringify(events)
        }

        override fun sendPointer(events: JsonArray) {
            pointer += JsonJs.stringify(events)
        }

        override fun writeClipboard(text: String) {
            clipboard += text
        }

        override fun end(reason: String) {
            ended += reason
        }

        override fun saveQuality(quality: Quality) {
            savedQuality = quality
        }

        override fun saveClipboardSync(enabled: Boolean) {
            savedSync = enabled
        }
    }

    private val link = FakeLink()
    private var peerVersion = "1.2.0"
    private val core by lazy {
        ViewerCore(
            link = link,
            scheduler = clock,
            peer = PeerInfo(id = "123456789", name = "Bureau", platform = "win32", version = peerVersion),
            caps = Caps(control = true, files = true, clipboard = true, audio = false),
            quality = Quality.BALANCED,
            clipboardSync = true,
            clock = { clock.now },
            newClipId = { "cid1" },
        )
    }

    private fun control(json: String) = core.onControl(JsonJs.parseObjectOrNull(json)!!)

    private fun connect() {
        core.start()
        core.onConnectionState(RtcState.CONNECTING)
        core.onConnectionState(RtcState.CONNECTED)
        core.onControlOpen()
        core.onFirstFrame()
    }

    @Test
    fun `hello on control open, like the desktop viewer`() {
        core.start()
        core.onControlOpen()
        assertEquals(listOf("{\"type\":\"hello\",\"quality\":\"balanced\",\"clipboard\":true}"), link.control)
    }

    @Test
    fun `phases - negotiating, waiting for the picture, live`() {
        core.start()
        assertEquals(ViewerPhase.NEGOTIATING, core.state.value.phase)
        assertFalse(core.canControl)
        core.onConnectionState(RtcState.CONNECTED)
        assertEquals(ViewerPhase.WAITING_VIDEO, core.state.value.phase)
        assertTrue(core.canControl)
        core.onFirstFrame()
        assertEquals(ViewerPhase.LIVE, core.state.value.phase)
    }

    @Test
    fun `no connection after 45 s ends the session as failed`() {
        core.start()
        clock.advance(ViewerCore.CONNECT_TIMEOUT_MS)
        assertEquals(ViewerPhase.ENDED, core.state.value.phase)
        assertEquals(EndReason.FAILED, core.state.value.endReason)
        assertEquals("ice-timeout", core.failure)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `a lost connection shows reconnecting after 1,5 s and survives an ICE restart`() {
        connect()
        core.input.key("ShiftLeft", true)
        core.onConnectionState(RtcState.DISCONNECTED)
        clock.advance(1_000)
        assertEquals(ViewerPhase.LIVE, core.state.value.phase)
        clock.advance(500)
        assertEquals(ViewerPhase.RECONNECTING, core.state.value.phase)
        assertFalse(core.canControl)
        // Everything held over there was released.
        assertEquals("[[\"r\"]]", link.input.last())
        // The host restarts ICE; the same session comes back.
        core.onConnectionState(RtcState.CONNECTED)
        assertEquals(ViewerPhase.LIVE, core.state.value.phase)
        clock.advance(60_000)
        assertEquals(ViewerPhase.LIVE, core.state.value.phase)
        assertTrue(link.ended.isEmpty())
    }

    @Test
    fun `a short drop is not even shown, a failed state shows at once`() {
        connect()
        core.onConnectionState(RtcState.DISCONNECTED)
        clock.advance(800)
        core.onConnectionState(RtcState.CONNECTED)
        clock.advance(5_000)
        assertEquals(ViewerPhase.LIVE, core.state.value.phase)
        core.onConnectionState(RtcState.FAILED)
        clock.advance(0)
        assertEquals(ViewerPhase.RECONNECTING, core.state.value.phase)
    }

    @Test
    fun `no reconnection within 30 s ends the session`() {
        connect()
        core.onConnectionState(RtcState.FAILED)
        clock.advance(ViewerCore.RECONNECT_TIMEOUT_MS)
        assertEquals(EndReason.FAILED, core.state.value.endReason)
        assertEquals("connection-lost", core.failure)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `a closed control channel counts as a disconnection`() {
        connect()
        core.onControlClose()
        clock.advance(1_500)
        assertEquals(ViewerPhase.RECONNECTING, core.state.value.phase)
    }

    @Test
    fun `info lists the screens and updates the permissions`() {
        connect()
        control(
            """{"type":"info","displays":[{"id":"10","width":1920,"height":1080,"primary":true},""" +
                """{"id":"11","width":2560,"height":1440,"primary":false}],"current":"10","perms":{"control":false,"clipboard":true}}""",
        )
        val s = core.state.value
        assertEquals(listOf(RemoteDisplay("10", 1920, 1080, true), RemoteDisplay("11", 2560, 1440, false)), s.displays)
        assertEquals("10", s.current)
        assertEquals(0, s.currentIndex)
        assertEquals(1, s.nextIndex)
        assertFalse(s.caps.control)
        assertTrue(s.caps.files) // absent from perms: unchanged
        assertFalse(core.canControl)
        core.nextDisplay()
        assertEquals("{\"type\":\"select-display\",\"displayId\":\"11\"}", link.control.last())
        control("""{"type":"display-changed","current":"11"}""")
        assertEquals(RemoteDisplay("11", 2560, 1440, false), core.state.value.currentDisplay)
        assertEquals(0, core.state.value.nextIndex)
    }

    @Test
    fun `quality changes are sent and remembered`() {
        connect()
        core.setQuality(Quality.SPEED)
        assertEquals("{\"type\":\"quality\",\"mode\":\"speed\"}", link.control.last())
        assertEquals(Quality.SPEED, link.savedQuality)
        assertEquals(Quality.SPEED, core.state.value.quality)
    }

    @Test
    fun `chat both ways, unread while the panel is closed`() = runBlocking {
        connect()
        control("""{"type":"chat","text":"Bonjour"}""")
        assertEquals(1, core.state.value.unread)
        val notice = withTimeout(1_000) { core.notices.first() }
        assertEquals(ViewerNotice.Chat("Bureau", "Bonjour"), notice)
        core.setChatOpen(true)
        assertEquals(0, core.state.value.unread)
        control("""{"type":"chat","text":"Ça va ?"}""")
        assertEquals(0, core.state.value.unread)
        assertTrue(core.sendChat("  Oui !  "))
        assertEquals("{\"type\":\"chat\",\"text\":\"Oui !\"}", link.control.last())
        assertFalse(core.sendChat("   "))
        assertEquals(listOf(false, false, true), core.state.value.chat.map { it.mine })
        assertEquals("x".repeat(ViewerCore.MAX_CHAT), run {
            control("""{"type":"chat","text":"${"x".repeat(3000)}"}""")
            core.state.value.chat.last().text
        })
    }

    @Test
    fun `the partner's clipboard text is copied here when synchronised`() = runBlocking {
        connect()
        control("""{"type":"clipboard","cid":"a1","text":"secret","html":"<b>secret</b>"}""")
        assertEquals(listOf("secret"), link.clipboard)
        assertEquals(ViewerNotice.ClipboardReceived, withTimeout(1_000) { core.notices.first() })
        // 1.0 / 1.1 hosts: text only, no cid.
        control("""{"type":"clipboard","text":"legacy"}""")
        assertEquals(listOf("secret", "legacy"), link.clipboard)
        // Files: not for a phone.
        control("""{"type":"clipboard","cid":"a2","files":[{"name":"a.txt","size":3,"dir":false}],"total":3}""")
        assertEquals(2, link.clipboard.size)
        // Sync off: ignored.
        core.setClipboardSync(false)
        assertEquals(false, link.savedSync)
        control("""{"type":"clipboard","cid":"a3","text":"other"}""")
        assertEquals(2, link.clipboard.size)
    }

    @Test
    fun `the phone's clipboard is sent on demand, with a cid for 1,2 hosts`() {
        connect()
        core.sendClipboard("hello")
        assertEquals("{\"type\":\"clipboard\",\"cid\":\"cid1\",\"text\":\"hello\"}", link.control.last())
        // Our own text echoed back is not written again.
        control("""{"type":"clipboard","cid":"zz","text":"hello"}""")
        assertTrue(link.clipboard.isEmpty())
        val before = link.control.size
        core.sendClipboard("")
        core.sendClipboard(null)
        core.sendClipboard("x".repeat(ViewerCore.MAX_CLIPBOARD_BYTES + 1))
        assertEquals(before, link.control.size)
    }

    @Test
    fun `older hosts get the plain clipboard message`() {
        peerVersion = "1.1.3"
        connect()
        core.sendClipboard("hi")
        assertEquals("{\"type\":\"clipboard\",\"text\":\"hi\"}", link.control.last())
    }

    @Test
    fun `input-blocked notices come and go`() {
        connect()
        control("""{"type":"input-blocked","reason":"elevated"}""")
        assertEquals("elevated", core.state.value.inputBlocked)
        control("""{"type":"input-blocked","reason":null}""")
        assertNull(core.state.value.inputBlocked)
    }

    @Test
    fun `statistics line and screen-to-screen delay`() {
        connect()
        control("""{"type":"host-stats","encodeMs":8,"pacerMs":4,"fps":30}""")
        core.onStats(ReceiverStats(rttMs = 40, fps = 29.6, relayed = false, jitterBufferMs = 10.0, decodeMs = 2.0))
        val stats = core.state.value.stats
        assertEquals(30, stats.fps)
        assertEquals(false, stats.relayed)
        // 40/2 + 8 + 4 + 10 + 2 + 16
        assertEquals(60, stats.delayMs)
        // Host statistics older than 3 s are not used.
        clock.advance(3_000)
        core.onStats(ReceiverStats(rttMs = 40, fps = 30.0, relayed = true))
        assertEquals(36, core.state.value.stats.delayMs)
        assertEquals(true, core.state.value.stats.relayed)
    }

    @Test
    fun `a frozen picture asks the host to wake its screen`() {
        connect()
        repeat(2) { core.onStats(ReceiverStats(fps = 0.0)) }
        assertFalse(link.control.any { it.contains("wake") })
        core.onStats(ReceiverStats(fps = 0.0))
        assertEquals("{\"type\":\"wake\"}", link.control.last())
        repeat(3) { core.onStats(ReceiverStats(fps = 0.0)) }
        assertTrue(core.state.value.frozen)
        core.onStats(ReceiverStats(fps = 25.0))
        assertFalse(core.state.value.frozen)
    }

    @Test
    fun `bye from the partner ends the session`() {
        connect()
        control("""{"type":"bye"}""")
        assertEquals(EndReason.PEER, core.state.value.endReason)
        assertEquals(listOf("closed"), link.ended)
        // Later messages are ignored.
        control("""{"type":"chat","text":"late"}""")
        assertTrue(core.state.value.chat.isEmpty())
    }

    @Test
    fun `disconnecting releases what is held, says bye and ends the signaling session`() {
        connect()
        core.input.press(MouseButton.LEFT, RemotePoint(0.5, 0.5))
        core.disconnect()
        assertEquals("[[\"r\"]]", link.input.last())
        assertEquals("{\"type\":\"bye\"}", link.control.last())
        assertEquals(listOf("closed"), link.ended)
        assertEquals(EndReason.USER, core.state.value.endReason)
        assertFalse(core.canControl)
        // Ending twice does nothing.
        core.disconnect()
        core.onSessionClosed("closed", byPeer = true)
        assertEquals(EndReason.USER, core.state.value.endReason)
        assertEquals(1, link.ended.size)
    }

    @Test
    fun `the signaling session closed by the partner ends the viewer`() {
        connect()
        core.onSessionClosed("host-closed", byPeer = true)
        assertEquals(EndReason.PEER, core.state.value.endReason)
        assertTrue(link.ended.isEmpty())
        clock.advance(100_000)
        assertEquals(0, clock.pending)
    }
}
