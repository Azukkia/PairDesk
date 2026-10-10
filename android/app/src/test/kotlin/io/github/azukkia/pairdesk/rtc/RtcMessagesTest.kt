package io.github.azukkia.pairdesk.rtc

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RtcMessagesTest {
    private val line = "candidate:842163049 1 udp 1677729535 203.0.113.7 51234 typ srflx raddr 192.168.1.20 rport 51234 generation 0 ufrag Ab3d network-id 1 network-cost 10"

    @Test
    fun `candidates have Chrome's toJSON shape`() {
        assertEquals(
            """{"type":"signal","data":{"candidate":{"candidate":"$line","sdpMid":"0","sdpMLineIndex":0,"usernameFragment":"Ab3d"}}}""",
            JsonJs.stringify(RtcSignals.candidate(line, "0", 0)),
        )
        val noUfrag = RtcSignals.candidate("candidate:1 1 udp 1 10.0.0.1 9 typ host", null, null)
        assertEquals(
            """{"type":"signal","data":{"candidate":{"candidate":"candidate:1 1 udp 1 10.0.0.1 9 typ host","sdpMid":null,"sdpMLineIndex":null}}}""",
            JsonJs.stringify(noUfrag),
        )
        // Parsed back like the desktop's messages.
        val parsed = SessionMessages.candidateOf(RtcSignals.candidate(line, "0", 0))!!
        assertEquals(line, parsed.candidate)
        assertEquals("0", parsed.sdpMid)
        assertEquals(0, parsed.sdpMLineIndex)
        assertEquals("Ab3d", parsed.usernameFragment)
    }

    @Test
    fun `descriptions`() {
        assertEquals(
            """{"type":"signal","data":{"description":{"type":"answer","sdp":"v=0\r\n"}}}""",
            JsonJs.stringify(RtcSignals.description("answer", "v=0\r\n")),
        )
    }

    @Test
    fun `candidate attributes`() {
        assertEquals("Ab3d", RtcSignals.ufragOf(line))
        assertEquals("srflx", RtcSignals.typeOf(line))
        assertNull(RtcSignals.ufragOf("candidate:1 1 udp 1 10.0.0.1 9 typ host ufrag"))
        assertNull(RtcSignals.typeOf(""))
    }

    @Test
    fun `ping is answered with the same t`() {
        assertEquals("""{"type":"pong","t":1234.5}""", JsonJs.stringify(RtcControl.pong(obj("""{"type":"ping","t":1234.5}"""))))
        assertEquals("""{"type":"pong"}""", JsonJs.stringify(RtcControl.pong(obj("""{"type":"ping"}"""))))
    }

    @Test
    fun `file offers are rejected`() {
        assertEquals("""{"type":"file-reject","fid":"a1b2"}""", JsonJs.stringify(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"a1b2","name":"x.txt","size":12}"""))!!))
        assertEquals("""{"type":"file-reject","fid":"a1"}""", JsonJs.stringify(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"a1","size":"0"}"""))!!))
        assertNull(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"","size":1}""")))
        assertNull(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"a","size":-1}""")))
        assertNull(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"a","size":1.5}""")))
        assertNull(RtcControl.fileReject(obj("""{"type":"file-offer","fid":"a"}""")))
    }

    @Test
    fun `description types`() {
        assertEquals(org.webrtc.SessionDescription.Type.OFFER, RtcSession.descriptionType("offer"))
        assertEquals(org.webrtc.SessionDescription.Type.ANSWER, RtcSession.descriptionType("answer"))
        assertNull(RtcSession.descriptionType("bogus"))
    }

    private fun obj(text: String) = JsonJs.parse(text) as JsonObject
}
