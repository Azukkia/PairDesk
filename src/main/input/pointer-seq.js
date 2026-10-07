// Mouse moves travel on an unordered "latest wins" data channel (no
// retransmission, so a lost packet never stalls the cursor), while clicks,
// keys and the final resting position use the reliable channel. Every
// position-bearing event carries a sequence number: a move older than the
// last applied position is dropped. Events without a number (viewers older
// than 1.1) are applied as they come.

export class PointerSequencer {
  constructor() {
    this.last = -1;
  }

  reset() {
    this.last = -1;
  }

  /** Returns true when the event must be applied. */
  accept(ev) {
    switch (ev[0]) {
      case 'm': {
        const seq = ev[3];
        if (!Number.isSafeInteger(seq)) return true;
        if (seq <= this.last) return false;
        this.last = seq;
        return true;
      }
      case 'd':
      case 'u': {
        const seq = ev[4];
        if (Number.isSafeInteger(seq) && seq > this.last) this.last = seq;
        return true;
      }
      default:
        return true;
    }
  }
}
