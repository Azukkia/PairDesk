// Mouse moves travel on an unordered "latest wins" data channel (no
// retransmission, so a lost packet never stalls the cursor), while clicks,
// keys and the final resting position use the reliable channel. Every
// position-bearing event carries a sequence number: a move older than the
// last applied position is dropped. A move also carries the number of the
// last press/release sent before it: while that click is still on its way
// (lost packet being retransmitted), the move is dropped too, so it can
// neither drag with a button that should be up nor click at a stale place.
// Events without numbers (viewers older than 1.1) are applied as they come.

export class PointerSequencer {
  constructor() {
    this.reset();
  }

  reset() {
    this.last = -1;
    this.button = 0;
  }

  /** Returns true when the event must be applied. */
  accept(ev) {
    switch (ev[0]) {
      case 'm': {
        const seq = ev[3];
        if (!Number.isSafeInteger(seq)) return true;
        if (seq <= this.last) return false;
        if (Number.isSafeInteger(ev[4]) && ev[4] > this.button) return false;
        this.last = seq;
        return true;
      }
      case 'd':
      case 'u': {
        const seq = ev[4];
        if (Number.isSafeInteger(seq)) {
          if (seq > this.last) this.last = seq;
          if (seq > this.button) this.button = seq;
        }
        return true;
      }
      default:
        return true;
    }
  }
}
