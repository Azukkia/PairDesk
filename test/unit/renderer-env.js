// Runs renderer modules (src/renderer/common) in Node for tests: a fake
// preload bridge (window.pairdesk) and in-memory WebRTC data channels.
// Renderers import "../shared/x.js", served from src/shared by app://.
// Import the renderer modules dynamically, after this one.

import { register } from 'node:module';

register(`data:text/javascript,${encodeURIComponent(`
export async function resolve(specifier, context, next) {
  if (specifier.startsWith('.') && context.parentURL?.includes('/src/renderer/')) {
    const url = new URL(specifier, context.parentURL).href;
    if (url.includes('/src/renderer/shared/')) return next(url.replace('/src/renderer/shared/', '/src/shared/'), context);
  }
  return next(specifier, context);
}`)}`);

/** window.pairdesk: records what is sent, answers invoke() with `handlers`. */
export const api = {
  sent: [],
  invoked: [],
  handlers: {},
  send(channel, ...args) {
    this.sent.push([channel, ...args]);
  },
  async invoke(channel, ...args) {
    this.invoked.push([channel, ...args]);
    const fn = this.handlers[channel];
    if (!fn) throw new Error(`no handler for ${channel}`);
    return fn(...args);
  },
  on() {
    return () => {};
  },
};
globalThis.window = { pairdesk: api };

/** One side of an in-memory data channel. */
export class FakeChannel {
  constructor(label) {
    this.label = label;
    this.readyState = 'connecting';
    this.bufferedAmount = 0;
    this.peer = null;
  }

  send(data) {
    if (this.readyState !== 'open') throw new Error('channel not open');
    const peer = this.peer;
    // Delivered even if the receiver closed meanwhile (worst case).
    setTimeout(() => peer.onmessage?.({ data }), 0);
  }

  close() {
    for (const ch of [this, this.peer]) {
      if (ch.readyState === 'closed') continue;
      ch.readyState = 'closed';
      setTimeout(() => ch.onclose?.(), 0);
    }
  }
}

export function channelPair(label) {
  const a = new FakeChannel(label);
  const b = new FakeChannel(label);
  a.peer = b;
  b.peer = a;
  return [a, b];
}

/**
 * RTCPeerConnection stand-in: data channels only. `maxChannels` channels
 * may be open at once (a real connection runs out of stream ids).
 */
export class FakePeerConnection {
  static maxChannels = Infinity;

  constructor() {
    this.peer = null;
    this.created = [];
  }

  createDataChannel(label) {
    if (this.created.filter((c) => c.readyState !== 'closed').length >= FakePeerConnection.maxChannels) {
      throw new Error('no stream id available');
    }
    const [local, remote] = channelPair(label);
    this.created.push(local);
    setTimeout(() => {
      this.peer.ondatachannel?.({ channel: remote });
      local.readyState = 'open';
      remote.readyState = 'open';
      local.onopen?.();
      remote.onopen?.();
    }, 0);
    return local;
  }

  close() {}
}
globalThis.RTCPeerConnection = FakePeerConnection;

/** Two RtcSessions joined by an open control channel. */
export function connectedPair(RtcSession) {
  const a = new RtcSession({ role: 'viewer', iceServers: [] });
  const b = new RtcSession({ role: 'viewer', iceServers: [] });
  a.pc.peer = b.pc;
  b.pc.peer = a.pc;
  const [ca, cb] = channelPair('control');
  ca.readyState = 'open';
  cb.readyState = 'open';
  a.pc.ondatachannel({ channel: ca });
  b.pc.ondatachannel({ channel: cb });
  return { a, b };
}

export const wait = (ms = 0) => new Promise((r) => setTimeout(r, ms));
