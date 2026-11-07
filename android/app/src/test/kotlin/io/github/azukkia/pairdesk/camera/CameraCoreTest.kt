package io.github.azukkia.pairdesk.camera

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.rtc.RtcState
import io.github.azukkia.pairdesk.viewer.EndReason
import io.github.azukkia.pairdesk.viewer.ManualScheduler
import io.github.azukkia.pairdesk.viewer.PeerInfo
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's side of a camera session, as test/e2e/camera.test.js plays it. */
class CameraCoreTest {
    private val clock = ManualScheduler()

    private class FakeLink : CameraLink {
        val control = ArrayList<String>()
        val ended = ArrayList<String>()
        var switches = 0
        var canSwitch = true

        override fun sendControl(message: JsonObject): Boolean {
            control += JsonJs.stringify(message)
            return true
        }

        override fun switchCamera(): Boolean {
            if (!canSwitch) return false
            switches++
            return true
        }

        override fun end(reason: String) {
            ended += reason
        }
    }

    private val link = FakeLink()
    private val core = CameraCore(link, clock, PeerInfo("123456789", "Bureau", "win32", "1.2.0"))

    private fun control(json: String) = core.onControl(JsonJs.parseObjectOrNull(json)!!)

    private val backInfo = """{"type":"camera-info","width":1280,"height":720,"facing":"back"}"""

    private fun started() {
        core.onCameraStarted(CameraFacing.BACK, 1280, 720, canSwitch = true)
        core.start()
    }

    @Test
    fun `camera-info when the control channel opens and for every camera-hello`() {
        started()
        assertEquals(CameraPhase.CONNECTING, core.state.value.phase)
        core.onControlOpen()
        control("""{"type":"camera-hello"}""")
        control("""{"type":"camera-hello"}""")
        assertEquals(listOf(backInfo, backInfo, backInfo), link.control)
    }

    @Test
    fun `no camera-info before the camera runs`() {
        core.onControlOpen()
        assertNull(core.info())
        assertTrue(link.control.isEmpty())
    }

    @Test
    fun `camera-switch from the computer switches, camera-info follows`() {
        started()
        control("""{"type":"camera-switch"}""")
        assertEquals(1, link.switches)
        assertTrue(core.state.value.switching)
        control("""{"type":"camera-switch"}""")
        assertEquals(1, link.switches, "one switch at a time")
        core.onSwitched(CameraFacing.FRONT, 1280, 720)
        assertEquals("""{"type":"camera-info","width":1280,"height":720,"facing":"front"}""", link.control.last())
        assertFalse(core.state.value.switching)
        assertTrue(core.switchCamera())
        core.onSwitchFailed()
        assertFalse(core.state.value.switching)
        assertEquals(CameraFacing.FRONT, core.state.value.facing)
    }

    @Test
    fun `a phone with one camera does not switch`() {
        core.onCameraStarted(CameraFacing.BACK, 1280, 720, canSwitch = false)
        assertFalse(core.switchCamera())
        assertEquals(0, link.switches)
    }

    @Test
    fun `the size of the pictures is announced when it changes`() {
        started()
        core.onFrameSize(1280, 720)
        assertTrue(link.control.isEmpty())
        core.onFrameSize(720, 1280) // the phone turned to portrait
        assertEquals("""{"type":"camera-info","width":720,"height":1280,"facing":"back"}""", link.control.single())
    }

    @Test
    fun `bye from the computer ends the session`() {
        started()
        control("""{"type":"bye"}""")
        assertEquals(CameraPhase.ENDED, core.state.value.phase)
        assertEquals(EndReason.PEER, core.state.value.endReason)
        assertEquals(listOf("closed"), link.ended)
        control("""{"type":"camera-hello"}""")
        assertTrue(link.control.isEmpty(), "nothing after the end")
    }

    @Test
    fun `the end button says bye`() {
        started()
        core.end()
        core.end()
        assertEquals(listOf("""{"type":"bye"}"""), link.control)
        assertEquals(listOf("closed"), link.ended)
        assertEquals(EndReason.USER, core.state.value.endReason)
    }

    @Test
    fun `connection phases and timeouts`() {
        started()
        core.onConnectionState(RtcState.CONNECTED)
        assertEquals(CameraPhase.LIVE, core.state.value.phase)
        core.onConnectionState(RtcState.DISCONNECTED)
        clock.advance(CameraCore.LOST_DELAY_MS)
        assertEquals(CameraPhase.RECONNECTING, core.state.value.phase)
        core.onConnectionState(RtcState.CONNECTED)
        clock.advance(CameraCore.RECONNECT_TIMEOUT_MS)
        assertEquals(CameraPhase.LIVE, core.state.value.phase)
        core.onConnectionState(RtcState.FAILED)
        clock.advance(CameraCore.RECONNECT_TIMEOUT_MS)
        assertEquals(CameraPhase.ENDED, core.state.value.phase)
        assertEquals("connection-lost", core.failure)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `no connection after 60 s`() {
        started()
        clock.advance(CameraCore.CONNECT_TIMEOUT_MS)
        assertEquals("ice-timeout", core.failure)
        assertEquals(EndReason.FAILED, core.state.value.endReason)
    }

    @Test
    fun `a camera failure ends the session`() {
        started()
        core.onCameraFailed("camera-error")
        assertEquals("camera-error", core.state.value.error)
        assertEquals(listOf("""{"type":"bye"}"""), link.control)
        assertEquals(listOf("failed"), link.ended)
    }

    @Test
    fun `back camera first, the other one for a switch`() {
        val devices = listOf(CameraDevice("1", front = true, back = false), CameraDevice("0", front = false, back = true), CameraDevice("2", front = false, back = true))
        assertEquals("0", CameraChoice.pick(devices)?.name)
        assertEquals("1", CameraChoice.pick(devices, CameraFacing.FRONT)?.name)
        assertEquals("1", CameraChoice.next(devices, devices[1])?.name)
        assertEquals("0", CameraChoice.next(devices, devices[0])?.name)
        val externals = listOf(CameraDevice("a", false, false), CameraDevice("b", false, false))
        assertEquals("a", CameraChoice.pick(externals, CameraFacing.FRONT)?.name)
        assertEquals("b", CameraChoice.next(externals, externals[0])?.name)
        assertNull(CameraChoice.next(listOf(devices[0]), devices[0]))
        assertNull(CameraChoice.pick(emptyList()))
    }

    @Test
    fun `1280x720 at 30 fps when possible`() {
        val formats = listOf(
            CameraFormat(1920, 1080, 30), CameraFormat(1280, 720, 30), CameraFormat(1280, 720, 60), CameraFormat(640, 480, 30),
        )
        assertEquals(CameraFormat(1280, 720, 30), CameraChoice.format(formats))
        assertEquals(CameraFormat(1280, 960, 15), CameraChoice.format(listOf(CameraFormat(1280, 960, 15), CameraFormat(4000, 3000, 10))))
        assertEquals(CameraFormat(1920, 1080, 30), CameraChoice.format(listOf(CameraFormat(1280, 720, 15), CameraFormat(1920, 1080, 30))))
        assertEquals(CameraFormat(1280, 720, 30), CameraChoice.format(emptyList()))
    }
}
