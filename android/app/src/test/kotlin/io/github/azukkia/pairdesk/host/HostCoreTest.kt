package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.rtc.RtcState
import io.github.azukkia.pairdesk.rtc.SenderStats
import io.github.azukkia.pairdesk.viewer.EndReason
import io.github.azukkia.pairdesk.viewer.ManualScheduler
import io.github.azukkia.pairdesk.viewer.PeerInfo
import io.github.azukkia.pairdesk.viewer.Quality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone as the host of a control session (docs/PROTOCOL.md 5.2, src/renderer/host/host.js). */
class HostCoreTest {
    private val clock = ManualScheduler()

    private class FakeLink : HostLink {
        val control = ArrayList<String>()
        val qualities = ArrayList<Quality>()
        val ended = ArrayList<String>()
        var restarts = 0
        var open = true

        override fun sendControl(message: JsonObject): Boolean {
            if (!open) return false
            control += JsonJs.stringify(message)
            return true
        }

        override fun restartIce() {
            restarts++
        }

        override fun applyQuality(quality: Quality) {
            qualities += quality
        }

        override fun end(reason: String) {
            ended += reason
        }
    }

    private val link = FakeLink()
    private val core = HostCore(
        link = link,
        scheduler = clock,
        peer = PeerInfo(id = "123456789", name = "Bureau", platform = "win32", version = "1.2.0"),
        width = 1080,
        height = 2400,
        control = true,
        clock = { clock.now },
    )

    private fun control(json: String) = core.onControl(JsonJs.parseObjectOrNull(json)!!)

    private val info = """{"type":"info","displays":[{"id":"android","width":1080,"height":2400,"primary":true}],""" +
        """"current":"android","perms":{"control":true,"files":false,"clipboard":false,"audio":false}}"""

    @Test
    fun `info when the control channel opens`() {
        core.start()
        core.onControlOpen()
        assertEquals(listOf(info), link.control)
    }

    @Test
    fun `rotation and control changes send a new info`() {
        core.onControlOpen()
        assertTrue(core.onScreenSize(2400, 1080))
        assertFalse(core.onScreenSize(2400, 1080), "same size")
        assertFalse(core.onScreenSize(0, 0))
        core.setControl(false)
        core.setControl(false)
        assertEquals(3, link.control.size)
        assertTrue(link.control[1].contains(""""width":2400,"height":1080"""))
        assertTrue(link.control[2].contains(""""perms":{"control":false"""))
        assertEquals(2400, core.state.value.width)
        assertFalse(core.state.value.control)
    }

    @Test
    fun `quality from hello and quality messages`() {
        control("""{"type":"hello","quality":"speed","clipboard":true}""")
        control("""{"type":"quality","mode":"quality"}""")
        control("""{"type":"quality","mode":"nonsense"}""")
        assertEquals(listOf(Quality.SPEED, Quality.QUALITY, Quality.BALANCED), link.qualities)
        assertEquals(1.5, HostCore.encoding(Quality.SPEED).scaleDown)
    }

    @Test
    fun `chat both ways`() = runBlocking {
        control("""{"type":"chat","text":"Bonjour"}""")
        control("""{"type":"chat","text":""}""")
        assertEquals("Bonjour", withTimeout(1000) { core.incomingChat.first() }.text)
        assertEquals(1, core.state.value.unread)
        core.setChatOpen(true)
        assertEquals(0, core.state.value.unread)
        assertTrue(core.sendChat("  Salut  "))
        assertFalse(core.sendChat("   "))
        assertEquals("""{"type":"chat","text":"Salut"}""", link.control.last())
        assertEquals(listOf("Bonjour" to false, "Salut" to true), core.state.value.chat.map { it.text to it.mine })
    }

    @Test
    fun `connection - live, ICE restart after 4 s disconnected, at once when failed`() {
        core.start()
        core.onConnectionState(RtcState.CONNECTING)
        core.onConnectionState(RtcState.CONNECTED)
        assertEquals(HostPhase.LIVE, core.state.value.phase)
        core.onConnectionState(RtcState.DISCONNECTED)
        assertEquals(HostPhase.RECONNECTING, core.state.value.phase)
        clock.advance(HostCore.RESTART_DELAY_MS - 1)
        assertEquals(0, link.restarts)
        clock.advance(1)
        assertEquals(1, link.restarts)
        core.onConnectionState(RtcState.FAILED)
        assertEquals(2, link.restarts)
        core.onConnectionState(RtcState.CONNECTED)
        clock.advance(HostCore.RECONNECT_TIMEOUT_MS)
        assertEquals(HostPhase.LIVE, core.state.value.phase)
        assertTrue(link.ended.isEmpty())
    }

    @Test
    fun `a connection that does not come back ends the session`() {
        core.start()
        core.onConnectionState(RtcState.CONNECTED)
        core.onConnectionState(RtcState.FAILED)
        clock.advance(HostCore.RECONNECT_TIMEOUT_MS)
        assertEquals(HostPhase.ENDED, core.state.value.phase)
        assertEquals(EndReason.FAILED, core.state.value.endReason)
        assertEquals("connection-lost", core.failure)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `no connection after 60 s`() {
        core.start()
        clock.advance(HostCore.CONNECT_TIMEOUT_MS)
        assertEquals("ice-timeout", core.failure)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `bye from the computer, end from the phone`() {
        control("""{"type":"bye"}""")
        assertEquals(EndReason.PEER, core.state.value.endReason)
        assertEquals(listOf("closed"), link.ended)
        core.stop()
        assertEquals(listOf("closed"), link.ended, "already over")

        val other = HostCore(link, clock, core.peer, 100, 100, control = false)
        other.stop()
        assertEquals("""{"type":"bye"}""", link.control.last())
        assertEquals(EndReason.USER, other.state.value.endReason)
        assertEquals("host-closed", link.ended.last())
        other.onControlOpen()
        assertEquals("""{"type":"bye"}""", link.control.last(), "nothing after the end")
    }

    @Test
    fun `a capture that cannot start or stops ends the session`() {
        core.onCaptureFailed()
        assertEquals(EndReason.FAILED, core.state.value.endReason)
        assertEquals("capture", core.failure)
        assertEquals(listOf("failed"), link.ended)

        val other = HostCore(link, clock, core.peer, 100, 100, control = false)
        other.onCaptureStopped()
        assertEquals(EndReason.USER, other.state.value.endReason)
        assertEquals("host-closed", link.ended.last())
    }

    @Test
    fun `host-stats once live, without unknown values`() {
        core.onSenderStats(SenderStats(fps = 30.0), 1.0)
        assertTrue(link.control.isEmpty())
        core.onConnectionState(RtcState.CONNECTED)
        core.onSenderStats(SenderStats(fps = 29.5, encodeMs = 4.0, codec = "VP8", bandwidth = 2_000_000.0), 1.0)
        val stats = JsonJs.parseObjectOrNull(link.control.single())!!
        assertEquals(listOf("type", "encodeMs", "fps", "codec", "bandwidth", "scale"), stats.keys.toList())
        assertEquals("host-stats", stats["type"]?.toString()?.trim('"'))
        assertEquals(29.5, stats["fps"].toString().toDouble())
    }
}
