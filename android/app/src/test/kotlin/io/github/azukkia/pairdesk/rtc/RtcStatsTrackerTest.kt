package io.github.azukkia.pairdesk.rtc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RtcStatsTrackerTest {
    private fun report(t: Double, bytes: Long, decoded: Long, decodeTime: Double, local: String = "host") = listOf(
        StatEntry("T", "transport", t, mapOf("selectedCandidatePairId" to "P")),
        StatEntry("P", "candidate-pair", t, mapOf("currentRoundTripTime" to 0.042, "localCandidateId" to "L", "remoteCandidateId" to "R", "availableOutgoingBitrate" to 2_000_000.0)),
        StatEntry("L", "local-candidate", t, mapOf("candidateType" to local)),
        StatEntry("R", "remote-candidate", t, mapOf("candidateType" to "srflx")),
        StatEntry("C", "codec", t, mapOf("mimeType" to "video/VP9")),
        StatEntry(
            "I",
            "inbound-rtp",
            t,
            mapOf(
                "kind" to "video", "bytesReceived" to java.math.BigInteger.valueOf(bytes), "framesPerSecond" to 30.0,
                "frameWidth" to 1920L, "frameHeight" to 1080L, "framesDecoded" to decoded, "totalDecodeTime" to decodeTime,
                "codecId" to "C", "decoderImplementation" to "c2.android.vp9.decoder",
            ),
        ),
    )

    @Test
    fun `receiver stats turn counters into rates`() {
        val tracker = RtcStatsTracker()
        val first = tracker.receiver(report(1_000_000.0, 0, 0, 0.0))
        assertNull(first.bitrate)
        assertEquals(42, first.rttMs)
        assertEquals(false, first.relayed)
        assertEquals("VP9", first.codec)
        assertEquals(1920, first.width)

        val second = tracker.receiver(report(2_000_000.0, 250_000, 30, 0.15, local = "relay"))
        assertEquals(2_000_000.0, second.bitrate!!, 0.001)
        assertEquals(5.0, second.decodeMs!!, 0.001)
        assertTrue(second.relayed!!)
    }
}
