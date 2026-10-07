// Input helper (utility process) logic: injection, gating, ports, cursor PNGs.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
import { createInputCore } from '../../src/main/helper/input-core.js';
import { encodePng, pngDataUrl } from '../../src/main/helper/png.js';
import { integrityFromLabel } from '../../src/main/input/win32-guard.js';

function fakeInjector() {
  const calls = [];
  const rec = (name) => (...args) => calls.push([name, ...args]);
  return {
    calls,
    available: true,
    moveTo: rec('move'),
    button: rec('button'),
    wheel: rec('wheel'),
    key: rec('key'),
    typeText: rec('text'),
    releaseAll: rec('release'),
    cursorPos: () => ({ x: 12, y: 34 }),
  };
}

function fakePort() {
  const sent = [];
  let onMessage = null;
  return {
    sent,
    closed: false,
    postMessage: (m) => sent.push(m),
    on: (ev, cb) => { if (ev === 'message') onMessage = cb; },
    start() {},
    close() { this.closed = true; },
    deliver: (data) => onMessage({ data }),
  };
}

const rect = { x: 100, y: 50, width: 1001, height: 501 };

test('input core: injects into the shared display only while control is allowed', () => {
  const inj = fakeInjector();
  const toMain = [];
  const core = createInputCore({ injector: inj, toMain: (m) => toMain.push(m), log() {} });
  core.handleRenderer([['m', 0.5, 0.5]]);
  assert.equal(inj.calls.length, 0, 'no session yet');

  core.handleMain({ type: 'session', id: 's1', control: false, rect });
  core.handleRenderer([['d', 0, 0.5, 0.5, 1], ['u', 0, 0.5, 0.5, 2]]);
  assert.equal(inj.calls.length, 0, 'control not allowed');

  core.handleMain({ type: 'session', id: 's1', control: true, rect });
  // Moves numbered before the clicks counted while suspended are stale.
  core.handleRenderer([['m', 0.2, 0.2, 2, 0]]);
  assert.equal(inj.calls.length, 0);
  core.handleRenderer([['m', 0, 0, 3, 2], ['m', 1, 1, 4, 2], ['d', 2, 0.5, 0.5, 5], ['u', 2, 0.5, 0.5, 6]]);
  assert.deepEqual(inj.calls, [
    ['move', 100, 50], ['move', 1100, 550],
    ['move', 600, 300], ['button', 2, true], ['move', 600, 300], ['button', 2, false],
  ]);
  inj.calls.length = 0;
  core.handleRenderer([['w', 0, 99999, 7], ['k', 'KeyA', 1], ['k', 'KeyA', 0], ['t', 'é😀'], ['r'], ['bogus'], 'x', null]);
  assert.deepEqual(inj.calls, [['wheel', 0, 2400], ['key', 'KeyA', true], ['key', 'KeyA', false], ['text', 'é😀'], ['release']]);

  // Fallback path through the main process needs the same session id.
  inj.calls.length = 0;
  core.handleMain({ type: 'input', id: 'other', events: [['m', 0.5, 0.5]] });
  core.handleMain({ type: 'input', id: 's1', events: [['m', 0.5, 0.5]] });
  assert.deepEqual(inj.calls, [['move', 600, 300]]);

  // Ending the session releases everything.
  inj.calls.length = 0;
  core.handleMain({ type: 'session', id: null });
  assert.deepEqual(inj.calls, [['release']]);
  core.handleRenderer([['m', 0.5, 0.5]]);
  assert.equal(inj.calls.length, 1);
});

test('input core: renderer port, cursor messages and queries', async () => {
  const inj = fakeInjector();
  const toMain = [];
  let started = null;
  let resyncs = 0;
  const tracker = {
    start(cb) { started = cb; },
    stop() { started = null; },
    resync() { resyncs++; },
    inspect: () => ({ shape: 'text' }),
    close() {},
  };
  let checks = 0;
  const guard = { check: () => (checks++ >= 1 ? 'elevated' : null) };
  const core = createInputCore({
    injector: inj, toMain: (m) => toMain.push(m), log() {}, createTracker: async () => tracker, guard, guardIntervalMs: 20,
  });
  const port = fakePort();
  core.handleMain({ type: 'port', id: 's1' }, [port]);
  assert.ok(port.closed, 'port for an unknown session is closed');

  core.handleMain({ type: 'session', id: 's1', control: true, rect, scale: 2 });
  assert.equal(core.scale, 2);
  const port2 = fakePort();
  core.handleMain({ type: 'port', id: 's1' }, [port2]);
  port2.deliver([['m', 0.5, 0.5]]);
  assert.deepEqual(inj.calls.at(-1), ['move', 600, 300]);
  await new Promise((r) => setTimeout(r, 60));
  assert.ok(started, 'cursor tracker started');
  started({ type: 'cursor', shape: 'text' });
  assert.deepEqual(port2.sent.find((m) => m.type === 'cursor'), { type: 'cursor', shape: 'text' });
  port2.deliver({ type: 'cursor-resync' });
  assert.ok(resyncs >= 1);
  // The guard reports Windows blocking input to the renderer and the main process.
  assert.deepEqual(port2.sent.filter((m) => m.type === 'input-state').at(-1), { type: 'input-state', blocked: 'elevated' });
  assert.deepEqual(toMain.find((m) => m.type === 'state'), { type: 'state', blocked: 'elevated' });

  core.handleMain({ type: 'query', rid: 7, what: 'cursor-pos' });
  core.handleMain({ type: 'query', rid: 8, what: 'cursor-debug' });
  assert.deepEqual(toMain.filter((m) => m.type === 'reply'), [
    { type: 'reply', rid: 7, value: { x: 12, y: 34 } },
    { type: 'reply', rid: 8, value: { shape: 'text' } },
  ]);

  // Control withdrawn: buttons released, tracker and guard stopped.
  core.handleMain({ type: 'session', id: 's1', control: false, rect });
  assert.equal(started, null);
  assert.deepEqual(port2.sent.at(-1), { type: 'input-state', blocked: null });
  // A new session replaces the port and the sequencer.
  core.handleMain({ type: 'session', id: 's2', control: true, rect });
  assert.ok(port2.closed);
  core.dispose();
});

test('png encoder: straight RGBA from premultiplied BGRA', () => {
  // 2x1: half-transparent red (premultiplied 128,0,0 → 255,0,0), opaque blue.
  const bgra = Buffer.from([0, 0, 128, 128, 255, 0, 0, 255]);
  const png = encodePng(bgra, 2, 1);
  assert.deepEqual([...png.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  // Walk the chunks and check every CRC.
  let off = 8;
  const chunks = {};
  while (off < png.length) {
    const len = png.readUInt32BE(off);
    const type = png.toString('ascii', off + 4, off + 8);
    const data = png.subarray(off + 8, off + 8 + len);
    assert.equal(png.readUInt32BE(off + 8 + len), zlib.crc32(png.subarray(off + 4, off + 8 + len)), type);
    chunks[type] = data;
    off += 12 + len;
  }
  assert.equal(chunks.IHDR.readUInt32BE(0), 2);
  assert.equal(chunks.IHDR.readUInt32BE(4), 1);
  const raw = zlib.inflateSync(chunks.IDAT);
  assert.deepEqual([...raw], [0, 255, 0, 0, 128, 0, 0, 255, 255]);
  assert.ok(pngDataUrl(bgra, 2, 1).startsWith('data:image/png;base64,iVBORw0KGgo'));
});

test('windows integrity level parsing', () => {
  const label = (rid, sidOffset) => {
    const b = Buffer.alloc(sidOffset + 12);
    b.set([1, 1, 0, 0, 0, 0, 0, 16], sidOffset);
    b.writeUInt32LE(rid, sidOffset + 8);
    return b;
  };
  assert.equal(integrityFromLabel(label(0x2000, 16), 8), 0x2000);
  assert.equal(integrityFromLabel(label(0x3000, 8), 4), 0x3000);
  assert.equal(integrityFromLabel(Buffer.alloc(32), 8), null);
});
