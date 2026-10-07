// Text clipboard synchronisation helper. Electron has no clipboard change
// event, so the clipboard is polled while at least one session uses it (on
// Windows the cheap clipboard sequence number avoids reading it every time).

import { EventEmitter } from 'node:events';

const MAX_TEXT = 1024 * 1024;

export class ClipboardSync extends EventEmitter {
  constructor({ clipboard, getSequence = null, intervalMs = 700, log }) {
    super();
    this.clipboard = clipboard;
    this.getSequence = getSequence;
    this.intervalMs = intervalMs;
    this.log = log;
    this.users = 0;
    this.timer = null;
    this.lastText = null;
    this.lastSeq = null;
  }

  acquire() {
    if (this.users++ === 0) {
      this.lastText = this.#read();
      this.lastSeq = this.getSequence?.() ?? null;
      this.timer = setInterval(() => this.#poll(), this.intervalMs);
    }
  }

  release() {
    if (this.users > 0 && --this.users === 0) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  #read() {
    try {
      return this.clipboard.readText();
    } catch {
      return null;
    }
  }

  #poll() {
    if (this.getSequence) {
      const seq = this.getSequence();
      if (seq === this.lastSeq) return;
      this.lastSeq = seq;
    }
    const text = this.#read();
    if (text == null || text === this.lastText) return;
    this.lastText = text;
    if (text.length && text.length <= MAX_TEXT) this.emit('change', text);
  }

  /** Writes text received from a partner without echoing it back. */
  setText(text) {
    if (typeof text !== 'string' || text.length > MAX_TEXT || text === this.lastText) return;
    try {
      this.clipboard.writeText(text);
      this.lastText = text;
      this.lastSeq = this.getSequence?.() ?? null;
    } catch (err) {
      this.log?.warn(`[clipboard] write failed: ${err.message}`);
    }
  }
}
