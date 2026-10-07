// Rate control for file transfers (pure logic, unit tested).
//
// Files share the peer connection with the mouse and keyboard: sending them as
// fast as possible fills the queues and delayed input by 8-33 s on a 4 Mbps
// link (measured). The sender therefore paces files with a token bucket whose
// rate follows the round trip time of small pings on the control channel
// (delay based, like LEDBAT): the rate drops as soon as queues build up.

export class FilePacer {
  constructor({ initialBps = 4e6, minBps = 5e5, maxBps = 2e8, now = () => performance.now() } = {}) {
    Object.assign(this, { minBps, maxBps, now });
    this.rate = initialBps;
    this.tokens = 0;
    this.last = now();
    this.rtts = [];
    this.lastAdjust = 0;
  }

  /** Lowest round trip of the last 30 s = the round trip without queueing. */
  get baseRtt() {
    return this.rtts.length ? Math.min(...this.rtts.map((r) => r.rtt)) : null;
  }

  onRtt(rtt) {
    const t = this.now();
    this.rtts.push({ rtt, t });
    while (this.rtts.length && t - this.rtts[0].t > 30_000) this.rtts.shift();
    if (t - this.lastAdjust < 200) return;
    this.lastAdjust = t;
    const queueing = rtt - this.baseRtt;
    if (queueing > 60) this.rate = Math.max(this.minBps, this.rate * 0.7);
    else if (queueing < 20) this.rate = Math.min(this.maxBps, this.rate * 1.15 + 250_000);
  }

  #refill() {
    const t = this.now();
    // At most 50 ms worth of burst.
    this.tokens = Math.min(this.tokens + ((t - this.last) / 1000) * this.rate, this.rate * 0.05);
    this.last = t;
  }

  /**
   * Milliseconds to wait before the next chunk may be sent (0 = now). The
   * bucket works on debt: a chunk goes as soon as the previous ones are paid
   * for, so chunks bigger than the burst allowance (slow rates) still go, and
   * the average rate stays exact even when the caller wakes up late.
   */
  delayFor() {
    this.#refill();
    return this.tokens >= 0 ? 0 : Math.ceil((-this.tokens / this.rate) * 1000);
  }

  consume(bytes) {
    this.tokens -= bytes * 8;
  }

  async take(bytes) {
    for (;;) {
      const wait = this.delayFor();
      if (wait <= 0) break;
      await new Promise((r) => setTimeout(r, Math.min(wait, 50)));
    }
    this.consume(bytes);
  }
}
