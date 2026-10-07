import { EventEmitter } from 'node:events';

/** In-memory signaling network. Several transports may listen on one ID. */
export class MemoryBus {
  constructor({ dropRate = 0 } = {}) {
    this.listeners = new Map();
    this.dropRate = dropRate;
    this.log = [];
  }

  transport() {
    return new MemoryTransport(this);
  }
}

export class MemoryTransport extends EventEmitter {
  constructor(bus) {
    super();
    this.bus = bus;
    this.state = 'offline';
  }

  start(id) {
    this.myId = id;
    if (!this.bus.listeners.has(id)) this.bus.listeners.set(id, new Set());
    this.bus.listeners.get(id).add(this);
    this.state = 'online';
  }

  stop() {
    this.bus.listeners.get(this.myId)?.delete(this);
    this.state = 'offline';
  }

  async send(to, data) {
    const targets = this.bus.listeners.get(to);
    if (!targets || !targets.size) throw Object.assign(new Error('offline'), { code: 'offline' });
    this.bus.log.push({ from: this.myId, to, data: structuredClone(data) });
    for (const t of targets) {
      const copy = structuredClone(data);
      setImmediate(() => t.emit('message', { from: this.myId, data: copy }));
    }
  }
}

export const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

/** Waits until `fn()` is truthy (CI machines can be slow: no fixed delays). */
export async function waitUntil(fn, { timeout = 5000, interval = 5 } = {}) {
  const deadline = Date.now() + timeout;
  while (!fn()) {
    if (Date.now() > deadline) throw new Error('waitUntil: timed out');
    await tick(interval);
  }
}
