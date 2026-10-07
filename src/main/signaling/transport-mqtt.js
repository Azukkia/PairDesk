// Signaling transport over public MQTT brokers (zero configuration).
//
// Every device listens on a topic derived from its ID. Messages are sent to
// all connected brokers at once (redundancy) and de-duplicated on reception.
// The brokers only ever see PAKE public values and encrypted payloads.

import { EventEmitter } from 'node:events';
import { createHash, randomBytes } from 'node:crypto';
import mqtt from 'mqtt';
import { isValidId } from '../../shared/protocol.js';

const MAX_PAYLOAD = 64 * 1024;
const PUBLISH_TIMEOUT_MS = 10_000;

export function topicFor(prefix, id) {
  return prefix + createHash('sha256').update(`pairdesk:${id}`).digest('hex').slice(0, 32);
}

export class MqttTransport extends EventEmitter {
  constructor({ brokers, prefix = 'pairdesk/v1/', log, clientOptions = {} }) {
    super();
    this.kind = 'public';
    this.brokers = brokers;
    this.prefix = prefix;
    this.log = log;
    this.clientOptions = clientOptions;
    this.entries = [];
    this.seen = new Map();
    this.state = 'offline';
    this.myId = null;
  }

  start(myId) {
    this.myId = myId;
    this.topic = topicFor(this.prefix, myId);
    this.#setState('connecting');
    for (const url of this.brokers) this.#connect(url);
  }

  stop() {
    for (const entry of this.entries) {
      try { entry.client.end(true); } catch { /* ignore */ }
    }
    this.entries = [];
    this.#setState('offline');
  }

  #connect(url) {
    const client = mqtt.connect(url, {
      clientId: `pd_${randomBytes(8).toString('hex')}`,
      clean: true,
      keepalive: 30,
      reconnectPeriod: 5000,
      connectTimeout: 12_000,
      resubscribe: false,
      protocolVersion: 4,
      ...this.clientOptions,
    });
    const entry = { url, client, connected: false };
    this.entries.push(entry);
    client.on('connect', () => {
      client.subscribe(this.topic, { qos: 1 }, (err) => {
        if (err) {
          this.log?.warn(`[mqtt] subscribe failed on ${url}: ${err.message}`);
          return;
        }
        entry.connected = true;
        this.log?.info(`[mqtt] connected to ${url}`);
        this.#updateState();
      });
    });
    const down = () => {
      if (entry.connected) this.log?.info(`[mqtt] disconnected from ${url}`);
      entry.connected = false;
      this.#updateState();
    };
    client.on('close', down);
    client.on('offline', down);
    client.on('error', (err) => this.log?.warn(`[mqtt] ${url}: ${err.message}`));
    client.on('message', (_topic, payload) => this.#onPayload(payload));
  }

  #onPayload(payload) {
    if (!payload || payload.length > MAX_PAYLOAD) return;
    let msg;
    try {
      msg = JSON.parse(payload.toString('utf8'));
    } catch {
      return;
    }
    if (!msg || typeof msg.mid !== 'string' || msg.mid.length > 64) return;
    if (!isValidId(msg.from) || !msg.data || typeof msg.data !== 'object') return;
    if (this.seen.has(msg.mid)) return;
    this.seen.set(msg.mid, Date.now());
    if (this.seen.size > 4000) {
      // Map keeps insertion order: drop the oldest half.
      let drop = 2000;
      for (const key of this.seen.keys()) {
        this.seen.delete(key);
        if (--drop === 0) break;
      }
    }
    this.emit('message', { from: msg.from, data: msg.data });
  }

  #updateState() {
    const online = this.entries.some((e) => e.connected);
    this.#setState(online ? 'online' : 'connecting');
  }

  #setState(state) {
    if (state === this.state) return;
    this.state = state;
    this.emit('state', state);
  }

  get connectedBrokers() {
    return this.entries.filter((e) => e.connected).map((e) => e.url);
  }

  async send(to, data) {
    const live = this.entries.filter((e) => e.connected);
    if (!live.length) throw Object.assign(new Error('Not connected to the PairDesk network'), { code: 'network' });
    const payload = JSON.stringify({ mid: randomBytes(12).toString('base64url'), from: this.myId, data });
    if (payload.length > MAX_PAYLOAD) throw Object.assign(new Error('Message too large'), { code: 'too-large' });
    const topic = topicFor(this.prefix, to);
    const attempts = live.map((e) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('publish timeout')), PUBLISH_TIMEOUT_MS);
      e.client.publish(topic, payload, { qos: 1 }, (err) => {
        clearTimeout(timer);
        if (err) reject(err);
        else resolve();
      });
    }));
    try {
      await Promise.any(attempts);
    } catch {
      throw Object.assign(new Error('Unable to deliver the message'), { code: 'network' });
    }
  }
}
