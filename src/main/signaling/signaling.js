// Session establishment on top of a transport (MQTT relays or PairDesk server).
//
//   controller                                   host
//   probe ───────────────────────────────────────▶
//         ◀─────────────────────────────── probe-ack       (is the partner online?)
//   hello {sid, ya} ─────────────────────────────▶          CPace share
//         ◀─────────── challenge {ybs[], tags[]}            one share per valid password
//   confirm {idx, tag, intro(enc)} ──────────────▶          key confirmation
//         ◀──────────────── sec{waiting|accepted|rejected}
//   sec{signal …} ◀────────────────────────────▶ sec{signal …}   (SDP / ICE, encrypted)
//
// The relay only sees CPace public values and AES-GCM ciphertexts. Because the
// SDP (and its DTLS fingerprint) travels encrypted and authenticated with the
// password-derived key, the relay cannot man-in-the-middle the WebRTC link.

import { EventEmitter } from 'node:events';
import { randomBytes } from 'node:crypto';
import { PROTOCOL_VERSION, isValidId } from '../../shared/protocol.js';
import {
  channelIdentifier, cpaceShare, cpaceSecret, deriveSessionKeys, confirmTag, tagsEqual,
} from '../crypto/cpace.js';
import { SecureChannel } from '../crypto/channel.js';

const b64 = (u8) => Buffer.from(u8).toString('base64url');
const unb64 = (s) => (typeof s === 'string' && s.length <= 128 ? new Uint8Array(Buffer.from(s, 'base64url')) : null);

export class SignalingError extends Error {
  constructor(code, message, extra = {}) {
    super(message || code);
    this.code = code;
    Object.assign(this, extra);
  }
}

/** Global brute-force protection for incoming password attempts. */
export class AuthLimiter {
  constructor({ windowMs = 30 * 60_000, threshold = 5, baseLockMs = 30_000, maxLockMs = 30 * 60_000, now = Date.now } = {}) {
    Object.assign(this, { windowMs, threshold, baseLockMs, maxLockMs, now });
    this.failures = [];
  }

  #prune() {
    const limit = this.now() - this.windowMs;
    this.failures = this.failures.filter((t) => t > limit);
  }

  recordFailure() {
    this.#prune();
    this.failures.push(this.now());
  }

  recordSuccess() {
    this.failures = [];
  }

  get recentFailures() {
    this.#prune();
    return this.failures.length;
  }

  /** Milliseconds before a new attempt is accepted (0 = not locked). */
  lockedFor() {
    this.#prune();
    const n = this.failures.length;
    if (n < this.threshold) return 0;
    const lock = Math.min(this.maxLockMs, this.baseLockMs * 2 ** (n - this.threshold));
    return Math.max(0, this.failures[n - 1] + lock - this.now());
  }
}

export class Signaling extends EventEmitter {
  /**
   * @param {object} o
   * @param {object} o.transport MqttTransport | WsTransport
   * @param {string} o.myId
   * @param {() => Array<{kind: string, prs: Uint8Array}>} o.getCredentials host passwords (as PRS)
   * @param {() => (string|null)} o.canAccept returns a refusal reason ('busy', 'disabled') or null
   */
  constructor({ transport, myId, getCredentials, canAccept, limiter = new AuthLimiter(), log, timeouts = {} }) {
    super();
    this.transport = transport;
    this.myId = myId;
    this.getCredentials = getCredentials;
    this.canAccept = canAccept || (() => null);
    this.limiter = limiter;
    this.log = log;
    this.timeouts = { probe: 8000, hello: 20_000, approval: 120_000, pending: 30_000, grace: 2500, ...timeouts };
    this.pendingHost = new Map();
    this.pendingCtrl = new Map();
    this.sessions = new Map();
    this.probes = new Map();
    this.onTransportMessage = ({ from, data }) => {
      try {
        this.#onMessage(from, data);
      } catch (err) {
        this.log?.warn(`[signaling] error handling ${data?.t}: ${err.stack || err.message}`);
      }
    };
    transport.on('message', this.onTransportMessage);
  }

  #abortPending(code) {
    for (const p of [...this.pendingCtrl.values()]) p.fail(code);
    for (const p of this.pendingHost.values()) clearTimeout(p.timer);
    this.pendingHost.clear();
    for (const p of this.probes.values()) {
      clearTimeout(p.timer);
      p.reject(new SignalingError(code));
    }
    this.probes.clear();
  }

  /** Switches to another transport, keeping established sessions. */
  setTransport(transport) {
    this.transport.off('message', this.onTransportMessage);
    this.#abortPending('network');
    this.transport = transport;
    transport.on('message', this.onTransportMessage);
  }

  dispose() {
    this.transport.off('message', this.onTransportMessage);
    this.#abortPending('cancelled');
  }

  #send(to, data) {
    return this.transport.send(to, data);
  }

  #sendQuiet(to, data) {
    this.#send(to, data).catch((err) => this.log?.warn(`[signaling] send ${data.t} to ${to} failed: ${err.message}`));
  }

  #onMessage(from, data) {
    if (!isValidId(from) || !data || typeof data.t !== 'string') return;
    if (typeof data.sid !== 'string' || data.sid.length < 8 || data.sid.length > 64) return;
    switch (data.t) {
      case 'probe':
        this.#sendQuiet(from, { t: 'probe-ack', sid: data.sid, v: PROTOCOL_VERSION });
        break;
      case 'probe-ack': {
        const p = this.probes.get(data.sid);
        if (p && p.peerId === from) {
          this.probes.delete(data.sid);
          clearTimeout(p.timer);
          p.resolve({ version: data.v });
        }
        break;
      }
      case 'hello':
        this.#onHello(from, data);
        break;
      case 'confirm':
        this.#onConfirm(from, data);
        break;
      case 'abort': {
        const pending = this.pendingHost.get(data.sid);
        if (pending && pending.from === from) {
          this.pendingHost.delete(data.sid);
          clearTimeout(pending.timer);
          this.emit('auth-failed', { peerId: from, failures: this.limiter.recentFailures });
        }
        break;
      }
      case 'challenge':
        this.#onChallenge(from, data);
        break;
      case 'denied': {
        const p = this.pendingCtrl.get(data.sid);
        if (p && p.peerId === from) p.fail(String(data.reason || 'rejected'), { remoteVersion: data.v, retryIn: data.retryIn });
        break;
      }
      case 'sec':
        this.#onSecure(from, data);
        break;
      default:
        break;
    }
  }

  // ───────────────────────────── host side ─────────────────────────────

  #deny(to, sid, reason, extra = {}) {
    this.#sendQuiet(to, { t: 'denied', sid, reason, v: PROTOCOL_VERSION, ...extra });
  }

  #onHello(from, data) {
    const { sid } = data;
    if (this.pendingHost.has(sid) || this.sessions.has(sid)) return; // duplicate
    if (data.v !== PROTOCOL_VERSION) return this.#deny(from, sid, 'version');
    const locked = this.limiter.lockedFor();
    if (locked > 0) return this.#deny(from, sid, 'locked', { retryIn: Math.ceil(locked / 1000) });
    const refusal = this.canAccept();
    if (refusal) return this.#deny(from, sid, refusal);
    if (this.pendingHost.size >= 4) return this.#deny(from, sid, 'busy');
    const ya = unb64(data.ya);
    if (!ya || ya.length !== 32) return this.#deny(from, sid, 'protocol');
    const credentials = this.getCredentials();
    if (!credentials.length) return this.#deny(from, sid, 'disabled');

    const ci = channelIdentifier(from, this.myId);
    const candidates = [];
    try {
      for (const cred of credentials.slice(0, 4)) {
        const { scalar, share } = cpaceShare(cred.prs, ci, sid);
        const k = cpaceSecret(scalar, ya);
        const keys = deriveSessionKeys({ sid, ctrlId: from, hostId: this.myId, ya, yb: share, k });
        const tag = confirmTag(keys.tagKeyHost, 'host-confirm', keys.transcript);
        candidates.push({ kind: cred.kind, keys, yb: share, tag });
      }
    } catch (err) {
      this.log?.warn(`[signaling] invalid hello from ${from}: ${err.message}`);
      return this.#deny(from, sid, 'protocol');
    }
    // Every handshake counts as a failed attempt until it is confirmed: a
    // controller that guesses wrong learns it from our tags and may never
    // send a confirmation, so we cannot wait for one to count the attempt.
    this.limiter.recordFailure();
    const timer = setTimeout(() => {
      if (this.pendingHost.delete(sid)) this.emit('auth-failed', { peerId: from, failures: this.limiter.recentFailures });
    }, this.timeouts.pending);
    timer.unref?.();
    this.pendingHost.set(sid, { from, candidates, timer });
    this.#sendQuiet(from, {
      t: 'challenge',
      sid,
      ybs: candidates.map((c) => b64(c.yb)),
      tags: candidates.map((c) => b64(c.tag)),
    });
  }

  #onConfirm(from, data) {
    const pending = this.pendingHost.get(data.sid);
    if (!pending || pending.from !== from) return;
    this.pendingHost.delete(data.sid);
    clearTimeout(pending.timer);
    const cand = Number.isInteger(data.idx) ? pending.candidates[data.idx] : null;
    const tag = unb64(data.tag);
    const ok = cand && tagsEqual(confirmTag(cand.keys.tagKeyCtrl, 'ctrl-confirm', cand.keys.transcript), tag);
    if (!ok) {
      this.log?.warn(`[signaling] authentication failed from ${from}`);
      this.#deny(from, data.sid, 'auth');
      this.emit('auth-failed', { peerId: from, failures: this.limiter.recentFailures });
      return;
    }
    const channel = new SecureChannel({
      sendKey: cand.keys.encHostToCtrl, recvKey: cand.keys.encCtrlToHost, sendDir: 1, sid: data.sid,
    });
    let intro = {};
    try {
      intro = channel.open(data.intro || {});
    } catch {
      this.#deny(from, data.sid, 'protocol');
      return;
    }
    this.limiter.recordSuccess();
    const session = {
      sid: data.sid,
      peerId: from,
      role: 'host',
      credential: cand.kind,
      channel,
      peerName: typeof intro.name === 'string' ? intro.name.slice(0, 64) : '',
      peerPlatform: typeof intro.platform === 'string' ? intro.platform.slice(0, 16) : '',
      peerVersion: typeof intro.appVersion === 'string' ? intro.appVersion.slice(0, 32) : '',
      // 'camera': a phone streams its camera to this computer (1.2+).
      kind: intro.kind === 'camera' ? 'camera' : 'control',
      startedAt: Date.now(),
    };
    this.sessions.set(session.sid, session);
    this.emit('incoming', session);
  }

  /** Host: tells the controller its request awaits the local user's decision. */
  notifyWaiting(sid) {
    return this.send(sid, { type: 'waiting' }).catch(() => {});
  }

  accept(sid, info = {}) {
    return this.send(sid, { type: 'accepted', ...info });
  }

  reject(sid, reason = 'rejected') {
    const s = this.sessions.get(sid);
    if (!s) return Promise.resolve();
    const p = this.send(sid, { type: 'rejected', reason }).catch(() => {});
    this.sessions.delete(sid);
    return p;
  }

  // ─────────────────────────── controller side ──────────────────────────

  probe(peerId, timeoutMs = this.timeouts.probe) {
    const sid = randomBytes(12).toString('base64url');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.probes.delete(sid);
        reject(new SignalingError('offline'));
      }, timeoutMs);
      this.probes.set(sid, { peerId, resolve, reject, timer });
      this.#send(peerId, { t: 'probe', sid, v: PROTOCOL_VERSION }).catch((err) => {
        clearTimeout(timer);
        this.probes.delete(sid);
        reject(new SignalingError(err.code === 'offline' ? 'offline' : 'network', err.message));
      });
    });
  }

  /**
   * Authenticates against `peerId` with the given PRS.
   * Resolves with the established session or rejects with a SignalingError
   * whose code is one of: auth, offline, network, busy, locked, version,
   * disabled, rejected, timeout, cancelled, protocol.
   */
  connect(peerId, prs, { intro = {}, onStatus = () => {}, signal } = {}) {
    const sid = randomBytes(18).toString('base64url');
    const ci = channelIdentifier(this.myId, peerId);
    const { scalar, share } = cpaceShare(prs, ci, sid);
    return new Promise((resolve, reject) => {
      const p = {
        sid, peerId, scalar, ya: share, intro, onStatus, stage: 'hello', channel: null, grace: null, timer: null,
        fail: (code, extra) => {
          if (!this.pendingCtrl.has(sid)) return;
          this.pendingCtrl.delete(sid);
          clearTimeout(p.timer);
          clearTimeout(p.grace);
          reject(new SignalingError(code, code, extra));
        },
        succeed: (session) => {
          this.pendingCtrl.delete(sid);
          clearTimeout(p.timer);
          clearTimeout(p.grace);
          resolve(session);
        },
      };
      this.pendingCtrl.set(sid, p);
      p.timer = setTimeout(() => p.fail('timeout'), this.timeouts.hello);
      if (signal) {
        if (signal.aborted) return p.fail('cancelled');
        signal.addEventListener('abort', () => p.fail('cancelled'), { once: true });
      }
      onStatus('authenticating');
      this.#send(peerId, { t: 'hello', sid, v: PROTOCOL_VERSION, ya: b64(share) })
        .catch((err) => p.fail(err.code === 'offline' ? 'offline' : 'network'));
    });
  }

  #onChallenge(from, data) {
    const p = this.pendingCtrl.get(data.sid);
    if (!p || p.peerId !== from || p.stage !== 'hello') return;
    const ybs = Array.isArray(data.ybs) ? data.ybs.slice(0, 4) : [];
    const tags = Array.isArray(data.tags) ? data.tags : [];
    let match = null;
    ybs.forEach((ybText, idx) => {
      if (match) return;
      const yb = unb64(ybText);
      const tag = unb64(tags[idx]);
      if (!yb || yb.length !== 32 || !tag) return;
      try {
        const k = cpaceSecret(p.scalar, yb);
        const keys = deriveSessionKeys({ sid: p.sid, ctrlId: this.myId, hostId: p.peerId, ya: p.ya, yb, k });
        if (tagsEqual(confirmTag(keys.tagKeyHost, 'host-confirm', keys.transcript), tag)) match = { idx, keys };
      } catch {
        /* invalid share: ignore */
      }
    });
    if (!match) {
      // Wrong password — or a forged answer on a public relay: give the real
      // host a short grace period to answer before reporting the failure.
      if (!p.grace) {
        p.grace = setTimeout(() => {
          this.#sendQuiet(p.peerId, { t: 'abort', sid: p.sid });
          p.fail('auth');
        }, this.timeouts.grace);
      }
      return;
    }
    clearTimeout(p.grace);
    p.grace = null;
    p.stage = 'confirm';
    p.channel = new SecureChannel({
      sendKey: match.keys.encCtrlToHost, recvKey: match.keys.encHostToCtrl, sendDir: 2, sid: p.sid,
    });
    const tag = confirmTag(match.keys.tagKeyCtrl, 'ctrl-confirm', match.keys.transcript);
    const intro = p.channel.seal({ type: 'intro', ...p.intro });
    clearTimeout(p.timer);
    p.timer = setTimeout(() => p.fail('timeout'), this.timeouts.approval);
    p.onStatus('authenticated');
    this.#send(from, { t: 'confirm', sid: p.sid, idx: match.idx, tag: b64(tag), intro })
      .catch(() => p.fail('network'));
  }

  // ───────────────────────────── both sides ─────────────────────────────

  #onSecure(from, data) {
    const pending = this.pendingCtrl.get(data.sid);
    if (pending && pending.peerId === from && pending.stage === 'confirm') {
      let msg;
      try {
        msg = pending.channel.open(data);
      } catch {
        return;
      }
      if (msg.type === 'waiting') pending.onStatus('waiting-approval');
      else if (msg.type === 'rejected') pending.fail(String(msg.reason || 'rejected'));
      else if (msg.type === 'accepted') {
        const session = {
          sid: pending.sid,
          peerId: from,
          role: 'controller',
          channel: pending.channel,
          info: msg,
          peerName: typeof msg.name === 'string' ? msg.name.slice(0, 64) : '',
          startedAt: Date.now(),
        };
        this.sessions.set(session.sid, session);
        pending.succeed(session);
      }
      return;
    }
    const session = this.sessions.get(data.sid);
    if (!session || session.peerId !== from) return;
    let msg;
    try {
      msg = session.channel.open(data);
    } catch (err) {
      this.log?.warn(`[signaling] dropped message for ${data.sid}: ${err.message}`);
      return;
    }
    if (msg?.type === 'bye') {
      this.sessions.delete(session.sid);
      this.emit('closed', session.sid, String(msg.reason || 'remote'));
      return;
    }
    this.emit('message', session.sid, msg);
  }

  send(sid, msg) {
    const session = this.sessions.get(sid);
    if (!session) return Promise.reject(new SignalingError('no-session'));
    const sealed = session.channel.seal(msg);
    return this.#send(session.peerId, { t: 'sec', sid, ...sealed });
  }

  close(sid, reason = 'closed') {
    const session = this.sessions.get(sid);
    if (!session) return;
    this.send(sid, { type: 'bye', reason }).catch(() => {});
    this.sessions.delete(sid);
  }
}
