package io.github.azukkia.pairdesk.rtc

import kotlin.math.roundToInt

/** One entry of a WebRTC stats report, independent of org.webrtc (unit tests). */
class StatEntry(
    val id: String,
    val type: String,
    /** Microseconds. */
    val timestampUs: Double,
    val members: Map<String, Any?>,
) {
    fun num(key: String): Double? = (members[key] as? Number)?.toDouble()

    fun str(key: String): String? = members[key] as? String
}

/** Receiver (viewer) side: what the controller sees, and where the delay comes from (RtcSession.stats() of rtc.js). */
data class ReceiverStats(
    val rttMs: Int? = null,
    val fps: Double? = null,
    /** Bits per second. */
    val bitrate: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** True when a TURN relay carries the media. */
    val relayed: Boolean? = null,
    val jitterBufferMs: Double? = null,
    val decodeMs: Double? = null,
    val freezes: Int? = null,
    val codec: String? = null,
    val decoder: String? = null,
)

/** Sender (host / camera) side: encoder and send-queue health (RtcSession.senderStats() of rtc.js). */
data class SenderStats(
    val fps: Double? = null,
    val encodeMs: Double? = null,
    val pacerMs: Double? = null,
    val limitation: String? = null,
    /** Available outgoing bitrate (bits per second). */
    val bandwidth: Double? = null,
    val rttMs: Int? = null,
    val codec: String? = null,
    val encoder: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val targetBitrate: Double? = null,
)

/**
 * Computes [ReceiverStats] / [SenderStats] from successive reports; keeps the
 * previous inbound / outbound entry to turn counters into rates.
 */
class RtcStatsTracker {
    private var lastInbound: StatEntry? = null
    private var lastOutbound: StatEntry? = null

    private class Pair(val selected: StatEntry?, val byId: Map<String, StatEntry>)

    private fun pairOf(report: Collection<StatEntry>): Pair {
        val byId = report.associateBy { it.id }
        val selected = report.firstNotNullOfOrNull { s ->
            if (s.type == "transport") s.str("selectedCandidatePairId")?.let(byId::get) else null
        }
        return Pair(selected, byId)
    }

    private fun rate(prev: StatEntry?, cur: StatEntry, num: String, den: String, factor: Double = 1.0): Double? {
        if (prev == null) return null
        val dd = (cur.num(den) ?: 0.0) - (prev.num(den) ?: 0.0)
        if (dd <= 0) return null
        return ((cur.num(num) ?: 0.0) - (prev.num(num) ?: 0.0)) / dd * factor
    }

    private fun codecName(byId: Map<String, StatEntry>, s: StatEntry): String? =
        s.str("codecId")?.let(byId::get)?.str("mimeType")?.removePrefix("video/")

    fun receiver(report: Collection<StatEntry>): ReceiverStats {
        val (selected, byId) = pairOf(report).let { it.selected to it.byId }
        var out = ReceiverStats()
        report.firstOrNull { it.type == "inbound-rtp" && it.str("kind") == "video" }?.let { s ->
            val prev = lastInbound
            val bitrate = if (prev != null && s.timestampUs > prev.timestampUs) {
                ((s.num("bytesReceived") ?: 0.0) - (prev.num("bytesReceived") ?: 0.0)) * 8 * 1_000_000 / (s.timestampUs - prev.timestampUs)
            } else {
                null
            }
            out = out.copy(
                fps = s.num("framesPerSecond"),
                width = s.num("frameWidth")?.toInt(),
                height = s.num("frameHeight")?.toInt(),
                bitrate = bitrate,
                jitterBufferMs = rate(prev, s, "jitterBufferDelay", "jitterBufferEmittedCount", 1000.0),
                decodeMs = rate(prev, s, "totalDecodeTime", "framesDecoded", 1000.0),
                freezes = s.num("freezeCount")?.toInt(),
                decoder = s.str("decoderImplementation"),
                codec = codecName(byId, s),
            )
            lastInbound = s
        }
        if (selected != null) out = out.copy(rttMs = rttOf(selected), relayed = relayedOf(selected, byId))
        return out
    }

    fun sender(report: Collection<StatEntry>): SenderStats {
        val (selected, byId) = pairOf(report).let { it.selected to it.byId }
        var out = SenderStats()
        report.firstOrNull { it.type == "outbound-rtp" && it.str("kind") == "video" }?.let { s ->
            val prev = lastOutbound
            out = out.copy(
                fps = s.num("framesPerSecond"),
                width = s.num("frameWidth")?.toInt(),
                height = s.num("frameHeight")?.toInt(),
                encodeMs = rate(prev, s, "totalEncodeTime", "framesEncoded", 1000.0),
                pacerMs = rate(prev, s, "totalPacketSendDelay", "packetsSent", 1000.0),
                limitation = s.str("qualityLimitationReason"),
                encoder = s.str("encoderImplementation"),
                targetBitrate = s.num("targetBitrate"),
                codec = codecName(byId, s),
            )
            lastOutbound = s
        }
        if (selected != null) out = out.copy(bandwidth = selected.num("availableOutgoingBitrate"), rttMs = rttOf(selected))
        return out
    }

    private fun rttOf(pair: StatEntry): Int? = pair.num("currentRoundTripTime")?.let { (it * 1000).roundToInt() }

    private fun relayedOf(pair: StatEntry, byId: Map<String, StatEntry>): Boolean {
        val local = pair.str("localCandidateId")?.let(byId::get)
        val remote = pair.str("remoteCandidateId")?.let(byId::get)
        return local?.str("candidateType") == "relay" || remote?.str("candidateType") == "relay"
    }
}
