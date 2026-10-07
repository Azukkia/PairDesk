// Host-side video adaptation (pure logic, unit tested).
//
// With screen content ('detail'), WebRTC keeps the full resolution whatever
// the bandwidth: on a slow uplink the encoder falls to 1-2 fps and huge frames
// wait in the send queue for up to 1-2 s. Measured fix: lower the resolution
// ourselves when the bandwidth estimate or the send-queue delay says so.

// [scale factor, go down to this level when the recent max bandwidth is
// below…, leave this level upwards when the recent min bandwidth is above…]
export const SCALE_LEVELS = [
  [1, Infinity, 0],
  [1.5, 2_000_000, 2_800_000],
  [2, 900_000, 1_300_000],
];

export class AdaptiveScaler {
  constructor({
    levels = SCALE_LEVELS,
    settleMs = 4000, // let the bandwidth estimate converge first
    holdMs = 6000, // minimum time between two changes
    fastHoldMs = 2000, // …unless the send queue is congested
    congestedPacerMs = 150, // send-queue delay per packet meaning "congested"
    clearPacerMs = 40, // below this the queue is considered empty
  } = {}) {
    Object.assign(this, { levels, settleMs, holdMs, fastHoldMs, congestedPacerMs, clearPacerMs });
    this.reset(0);
  }

  reset(now) {
    this.index = 0;
    this.startedAt = now;
    this.lastChange = now;
    this.bandwidth = [];
    this.pacer = [];
  }

  get factor() {
    return this.levels[this.index][0];
  }

  /**
   * Feeds one sample per second. `bandwidth` = availableOutgoingBitrate (bps,
   * may be null), `pacerMs` = average send-queue delay per packet over the last
   * second (may be null). Returns the new factor when it changed, else null.
   */
  update({ now, bandwidth, pacerMs }) {
    if (Number.isFinite(bandwidth) && bandwidth > 0) {
      this.bandwidth.push(bandwidth);
      if (this.bandwidth.length > 5) this.bandwidth.shift();
    }
    if (Number.isFinite(pacerMs)) {
      this.pacer.push(pacerMs);
      if (this.pacer.length > 3) this.pacer.shift();
    }
    if (now - this.startedAt < this.settleMs) return null;

    const since = now - this.lastChange;
    const congested = this.pacer.length > 0 && this.pacer[this.pacer.length - 1] > this.congestedPacerMs;
    const recentMax = this.bandwidth.length ? Math.max(...this.bandwidth.slice(-3)) : Infinity;
    const recentMin = this.bandwidth.length ? Math.min(...this.bandwidth) : 0;

    let next = this.index;
    while (next + 1 < this.levels.length && recentMax < this.levels[next + 1][1]) next++;
    if (next === this.index && congested && next + 1 < this.levels.length) next++;
    if (next > this.index && (since >= this.holdMs || (congested && since >= this.fastHoldMs))) {
      return this.#set(next, now);
    }

    const queueClear = this.pacer.length === 0 || Math.max(...this.pacer) < this.clearPacerMs;
    if (
      next === this.index && this.index > 0 && since >= this.holdMs && queueClear
      && this.bandwidth.length >= 5 && recentMin > this.levels[this.index][2]
    ) {
      return this.#set(this.index - 1, now);
    }
    return null;
  }

  #set(index, now) {
    this.index = index;
    this.lastChange = now;
    // Old samples describe the previous resolution.
    this.pacer = [];
    return this.factor;
  }
}

/**
 * Codec order for screen content (measured: VP9 keeps 24-27 fps where VP8
 * falls to 2-4 fps on text; software H.264 is unusable). rtx/red/fec entries
 * are kept so retransmissions still work.
 */
export function rankCodecs(codecs, first = 'vp9') {
  const rank = (c) => {
    const mime = c.mimeType.toLowerCase();
    if (mime === `video/${first}` && (first !== 'vp9' || /profile-id=0/.test(c.sdpFmtpLine || 'profile-id=0'))) return 0;
    if (mime === 'video/vp9') return /profile-id=0/.test(c.sdpFmtpLine || 'profile-id=0') ? 1 : 4;
    if (mime === 'video/vp8') return 2;
    if (mime === 'video/av1') return 3;
    if (mime === 'video/h264') return 5;
    return 6; // rtx, red, ulpfec, flexfec
  };
  return [...codecs].sort((a, b) => rank(a) - rank(b));
}

/**
 * Detects a host that cannot encode VP9 fast enough: CPU-limited most of the
 * last 10 s with a poor frame rate. PairDesk then renegotiates with VP8.
 */
export class CpuWatch {
  constructor({ window = 10, threshold = 8, minFps = 15 } = {}) {
    Object.assign(this, { window, threshold, minFps });
    this.samples = [];
  }

  update({ limitation, fps }) {
    this.samples.push(limitation === 'cpu' && Number.isFinite(fps) && fps < this.minFps);
    if (this.samples.length > this.window) this.samples.shift();
    return this.samples.length === this.window && this.samples.filter(Boolean).length >= this.threshold;
  }

  reset() {
    this.samples = [];
  }
}
