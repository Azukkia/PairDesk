// Low-latency pipeline: adaptive resolution, codec order, file pacing,
// pointer sequencing and remote cursor shapes.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { AdaptiveScaler, CpuWatch, rankCodecs } from '../../src/renderer/common/adaptive.js';
import { FilePacer } from '../../src/renderer/common/file-pacer.js';
import { PointerSequencer } from '../../src/main/input/pointer-seq.js';
import { CSS_CURSORS, WIN_IDC_TO_CSS, X11_NAME_TO_CSS, cssFromX11Name } from '../../src/shared/cursor-shapes.js';
import { versionAtLeast, QUALITY_PRESETS } from '../../src/shared/protocol.js';
import { createCursorTracker } from '../../src/main/cursor.js';
import { ClipboardSync } from '../../src/main/clipboard.js';

// Renderer modules import '../shared/x.js', which the app:// protocol maps to
// src/shared: rewrite those specifiers to load them in Node.
async function importRenderer(rel) {
  const url = new URL(`../../src/renderer/${rel}`, import.meta.url);
  const shared = new URL('../../src/shared/', import.meta.url).href;
  const src = fs.readFileSync(url, 'utf8').replaceAll("'../shared/", `'${shared}`);
  return import(`data:text/javascript;base64,${Buffer.from(src).toString('base64')}`);
}

test('adaptive scaler: waits for the estimate to settle, then scales down on a slow uplink', () => {
  const s = new AdaptiveScaler();
  s.reset(0);
  assert.equal(s.update({ now: 1000, bandwidth: 600_000, pacerMs: 5 }), null);
  assert.equal(s.update({ now: 2000, bandwidth: 600_000, pacerMs: 5 }), null);
  assert.equal(s.factor, 1);
  // Past settleMs and holdMs: straight to the lowest level that fits.
  let changed = null;
  for (let now = 3000; now <= 7000 && changed == null; now += 1000) changed = s.update({ now, bandwidth: 600_000, pacerMs: 5 });
  assert.equal(changed, 2);
  assert.equal(s.factor, 2);
});

test('adaptive scaler: medium uplink picks 1.5, fast uplink stays at full resolution', () => {
  const medium = new AdaptiveScaler();
  const fast = new AdaptiveScaler();
  const results = [];
  for (let now = 0; now <= 10_000; now += 1000) {
    const m = medium.update({ now, bandwidth: 1_500_000, pacerMs: 3 });
    if (m != null) results.push(m);
    assert.equal(fast.update({ now, bandwidth: 20_000_000, pacerMs: 2 }), null);
  }
  assert.deepEqual(results, [1.5]);
  assert.equal(fast.factor, 1);
});

test('adaptive scaler: a congested send queue steps down even with a good estimate', () => {
  const s = new AdaptiveScaler();
  let now = 0;
  for (; now < 6000; now += 1000) s.update({ now, bandwidth: 5_000_000, pacerMs: 2 });
  assert.equal(s.factor, 1);
  assert.equal(s.update({ now, bandwidth: 5_000_000, pacerMs: 400 }), 1.5);
  // Still congested: next step after fastHoldMs (2 s), not holdMs (6 s).
  assert.equal(s.update({ now: now + 1000, bandwidth: 5_000_000, pacerMs: 400 }), null);
  assert.equal(s.update({ now: now + 2000, bandwidth: 5_000_000, pacerMs: 400 }), 2);
  assert.equal(s.update({ now: now + 5000, bandwidth: 5_000_000, pacerMs: 400 }), null, 'already at the lowest level');
});

test('adaptive scaler: goes back up only with a clear queue and a sustained higher estimate', () => {
  const s = new AdaptiveScaler();
  let now = 0;
  for (; now <= 8000; now += 1000) s.update({ now, bandwidth: 500_000, pacerMs: 10 });
  assert.equal(s.factor, 2);
  // Bandwidth recovers but the queue is not clear: stay.
  for (let i = 0; i < 8; i++, now += 1000) assert.equal(s.update({ now, bandwidth: 3_500_000, pacerMs: 80 }), null);
  // Clear queue: one level at a time, at most every holdMs.
  const ups = [];
  for (let i = 0; i < 20; i++, now += 1000) {
    const r = s.update({ now, bandwidth: 3_500_000, pacerMs: 5 });
    if (r != null) ups.push({ r, now });
  }
  assert.deepEqual(ups.map((u) => u.r), [1.5, 1]);
  assert.ok(ups[1].now - ups[0].now >= 6000);
});

test('adaptive scaler: no oscillation around a threshold (hysteresis)', () => {
  const s = new AdaptiveScaler();
  const changes = [];
  for (let now = 0; now <= 60_000; now += 1000) {
    // Alternates just around the 1.5 thresholds (2.0 Mbps down / 2.8 Mbps up).
    const bandwidth = (now / 1000) % 2 ? 1_950_000 : 2_300_000;
    const r = s.update({ now, bandwidth, pacerMs: 5 });
    if (r != null) changes.push(r);
  }
  assert.ok(changes.length <= 1, `changes: ${changes}`);
});

test('adaptive scaler: ignores missing samples', () => {
  const s = new AdaptiveScaler();
  for (let now = 0; now <= 20_000; now += 1000) assert.equal(s.update({ now, bandwidth: null, pacerMs: null }), null);
  assert.equal(s.factor, 1);
});

test('codec ranking: VP9 profile 0 first, rtx kept, VP8 when asked', () => {
  const codecs = [
    { mimeType: 'video/H264', sdpFmtpLine: 'profile-level-id=42e01f' },
    { mimeType: 'video/rtx', sdpFmtpLine: 'apt=96' },
    { mimeType: 'video/VP8' },
    { mimeType: 'video/VP9', sdpFmtpLine: 'profile-id=2' },
    { mimeType: 'video/VP9', sdpFmtpLine: 'profile-id=0' },
    { mimeType: 'video/AV1' },
    { mimeType: 'video/red' },
  ];
  const vp9 = rankCodecs(codecs).map((c) => `${c.mimeType} ${c.sdpFmtpLine ?? ''}`.trim());
  assert.deepEqual(vp9.slice(0, 3), ['video/VP9 profile-id=0', 'video/VP8', 'video/AV1']);
  assert.ok(vp9.includes('video/rtx apt=96') && vp9.includes('video/red'));
  assert.equal(vp9.length, codecs.length);
  const vp8 = rankCodecs(codecs, 'vp8');
  assert.equal(vp8[0].mimeType, 'video/VP8');
  assert.equal(vp8[1].sdpFmtpLine, 'profile-id=0');
  assert.equal(codecs[0].mimeType, 'video/H264', 'input not mutated');
});

test('cpu watch: needs a sustained CPU limitation with a low frame rate', () => {
  const w = new CpuWatch();
  for (let i = 0; i < 9; i++) assert.equal(w.update({ limitation: 'cpu', fps: 5 }), false);
  assert.equal(w.update({ limitation: 'cpu', fps: 5 }), true);
  w.reset();
  for (let i = 0; i < 20; i++) assert.equal(w.update({ limitation: 'cpu', fps: 25 }), false, 'fps fine');
  for (let i = 0; i < 20; i++) assert.equal(w.update({ limitation: i % 3 ? 'bandwidth' : 'cpu', fps: 5 }), false, 'mostly bandwidth');
});

test('file pacer: token bucket rate and delay based rate control', () => {
  let now = 0;
  const p = new FilePacer({ initialBps: 4e6, now: () => now });
  assert.equal(p.delayFor(), 0);
  p.consume(16384);
  // The next chunk waits until this one is paid for: 16 KiB at 4 Mbps ≈ 33 ms.
  const d = p.delayFor();
  assert.ok(d >= 30 && d <= 35, `delay ${d}`);
  now += 40;
  assert.equal(p.delayFor(), 0);
  // Burst capped at 50 ms worth of tokens.
  now += 10_000;
  p.delayFor();
  assert.ok(p.tokens <= p.rate * 0.05 + 1);

  // Queueing (RTT well above the base) lowers the rate, an empty queue raises it.
  const r0 = p.rate;
  p.onRtt(20);
  now += 250;
  p.onRtt(150);
  assert.ok(p.rate < r0, 'rate decreased');
  const r1 = p.rate;
  now += 250;
  p.onRtt(22);
  assert.ok(p.rate > r1, 'rate increased');
  // At most one adjustment per 200 ms.
  const r2 = p.rate;
  now += 50;
  p.onRtt(500);
  assert.equal(p.rate, r2);
  // Bounds.
  for (let i = 0; i < 100; i++) { now += 250; p.onRtt(1000); }
  assert.equal(p.rate, p.minBps);
  // The base RTT forgets samples older than 30 s.
  now += 31_000;
  p.onRtt(300);
  assert.equal(p.baseRtt, 300);
});

test('file pacer: slow rates still send (bucket goes into debt), at the right average', () => {
  for (const rate of [5e5, 1.2e6, 2e6, 2.6e6, 8e6]) {
    let now = 0;
    const p = new FilePacer({ initialBps: rate, now: () => now });
    p.rate = rate;
    let sent = 0;
    for (let step = 0; step < 60_000; step += 7) { // the caller wakes up late
      now = step;
      if (p.delayFor() === 0) {
        p.consume(16384);
        sent += 16384;
      }
    }
    const bps = (sent * 8) / 60;
    assert.ok(bps > rate * 0.95 && bps < rate * 1.05, `rate ${rate}: ${bps}`);
  }
});

test('file pacer: take() waits for tokens', async () => {
  const p = new FilePacer({ initialBps: 8e6 });
  const t0 = performance.now();
  for (let i = 0; i < 5; i++) await p.take(16384); // 5 × 16 ms
  const elapsed = performance.now() - t0;
  assert.ok(elapsed >= 50 && elapsed < 400, `elapsed ${elapsed}`);
});

test('pointer sequencer: moves that overtook a click still in flight are dropped', () => {
  const s = new PointerSequencer();
  assert.ok(s.accept(['m', 0.1, 0.1, 1, 0]));
  // Click at A (d 2, u 3) whose packets are late; moves 4-6 to C arrive first.
  assert.ok(!s.accept(['m', 0.5, 0.5, 4, 3]), 'release not applied yet');
  assert.ok(!s.accept(['m', 0.7, 0.7, 6, 3]));
  assert.ok(s.accept(['d', 0, 0.1, 0.1, 2]));
  assert.ok(!s.accept(['m', 0.7, 0.7, 6, 3]), 'press applied, release still missing');
  assert.ok(s.accept(['u', 0, 0.1, 0.1, 3]));
  // The reliable resting position (new number) restores C.
  assert.ok(s.accept(['m', 0.7, 0.7, 7]));
  assert.ok(s.accept(['m', 0.8, 0.8, 8, 3]));
  // A viewer reconnecting to the same host session restarts its button numbers.
  assert.ok(s.accept(['m', 0.9, 0.9, 9, 0]));
});

test('pointer sequencer: the wheel is a barrier too', () => {
  const s = new PointerSequencer();
  assert.ok(s.accept(['m', 0.2, 0.5, 1, 0]));
  // Scroll at (0.8, 0.5) = reliable [m 2, w 2]; the move to 0.45 sent after it arrives first.
  assert.ok(!s.accept(['m', 0.45, 0.44, 3, 2]), 'waits for the wheel');
  assert.ok(s.accept(['m', 0.8, 0.5, 2]));
  assert.ok(s.accept(['w', 0, 120, 2]));
  assert.ok(s.accept(['m', 0.45, 0.44, 4, 2]));
  // Wheel from viewers older than 1.1.
  assert.ok(s.accept(['w', 0, 120]));
});

test('pointer sequencer: drops stale moves, keeps clicks and old viewers', () => {
  const s = new PointerSequencer();
  assert.ok(s.accept(['m', 0.1, 0.1, 1]));
  assert.ok(s.accept(['m', 0.2, 0.2, 3]));
  assert.ok(!s.accept(['m', 0.15, 0.15, 2]), 'older move dropped');
  assert.ok(!s.accept(['m', 0.2, 0.2, 3]), 'duplicate (reliable resend) dropped');
  assert.ok(s.accept(['d', 0, 0.3, 0.3, 4]));
  assert.ok(!s.accept(['m', 0.25, 0.25, 4]), 'move older than the click position');
  assert.ok(s.accept(['u', 0, 0.3, 0.3, 5]));
  assert.ok(s.accept(['m', 0.4, 0.4, 6]));
  // Viewers without sequence numbers.
  assert.ok(s.accept(['m', 0.5, 0.5]));
  assert.ok(s.accept(['d', 0, 0.5, 0.5]));
  assert.ok(s.accept(['k', 'KeyA', true]));
  assert.ok(s.accept(['w', 0, 120]));
  s.reset();
  assert.ok(s.accept(['m', 0.1, 0.1, 1]));
});

test('cursor shapes: every mapping targets a known CSS keyword', () => {
  for (const css of Object.values(WIN_IDC_TO_CSS)) assert.ok(CSS_CURSORS.has(css), css);
  for (const css of Object.values(X11_NAME_TO_CSS)) assert.ok(CSS_CURSORS.has(css), css);
  assert.equal(WIN_IDC_TO_CSS[32513], 'text');
  assert.equal(WIN_IDC_TO_CSS[32649], 'pointer');
  assert.equal(cssFromX11Name('xterm'), 'text');
  assert.equal(cssFromX11Name('hand2'), 'pointer');
  assert.equal(cssFromX11Name('LEFT_PTR'), 'default');
  assert.equal(cssFromX11Name('some-unknown-name'), null);
  assert.equal(cssFromX11Name(''), null);
});

test('versionAtLeast', () => {
  assert.ok(versionAtLeast('1.1.0', '1.1.0'));
  assert.ok(versionAtLeast('1.10.0', '1.9.3'));
  assert.ok(versionAtLeast('2.0.0', '1.1.0'));
  assert.ok(versionAtLeast('1.1.0-beta.1', '1.1.0'));
  assert.ok(!versionAtLeast('1.0.9', '1.1.0'));
  assert.ok(!versionAtLeast(undefined, '1.1.0'));
  assert.ok(!versionAtLeast('', '1.1.0'));
});

test('quality presets are consistent', () => {
  for (const [name, p] of Object.entries(QUALITY_PRESETS)) {
    assert.ok(p.maxBitrate > 0 && p.maxFramerate > 0, name);
    assert.ok(['motion', 'detail', 'text', ''].includes(p.contentHint), name);
    assert.ok(['maintain-framerate', 'maintain-resolution', 'balanced'].includes(p.degradation), name);
  }
});

test('cursor tracker: sends changes only, images once per id, resync', async () => {
  let state = { handle: 1, shape: 'default' };
  const probe = { read: () => state, close() { this.closed = true; } };
  const sent = [];
  let encoded = 0;
  const tracker = await createCursorTracker({
    probe, intervalMs: 5, scale: () => 2,
    encodePng: () => { encoded++; return 'data:image/png;base64,AAAA'; },
  });
  tracker.start((m) => sent.push(m));
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(sent, [{ type: 'cursor', shape: 'default' }]);
  state = { handle: 2, shape: 'text' };
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(sent.at(-1), { type: 'cursor', shape: 'text' });
  assert.equal(sent.length, 2);

  const img = Buffer.alloc(4 * 4 * 4, 0xff);
  state = { serial: 3, bgra: img, width: 4, height: 4, xhot: 1, yhot: 2 };
  await new Promise((r) => setTimeout(r, 30));
  const custom = sent.at(-1);
  assert.equal(custom.shape, 'custom');
  assert.deepEqual(custom.hot, [1, 2]);
  assert.equal(custom.scale, 2);
  assert.ok(custom.png);
  state = { handle: 4, shape: 'text' };
  await new Promise((r) => setTimeout(r, 30));
  state = { serial: 5, bgra: img, width: 4, height: 4, xhot: 1, yhot: 2 };
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(sent.at(-1).id, custom.id);
  assert.equal(sent.at(-1).png, undefined, 'bitmap sent once');
  assert.equal(encoded, 1);

  // A transparent image means "hidden"; a too big one falls back to default.
  state = { serial: 6, bgra: Buffer.alloc(64), width: 4, height: 4, xhot: 0, yhot: 0 };
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(sent.at(-1), { type: 'cursor', shape: 'none' });
  state = { serial: 7, bgra: Buffer.alloc(4 * 200 * 200, 0xff), width: 200, height: 200, xhot: 0, yhot: 0 };
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(sent.at(-1), { type: 'cursor', shape: 'default' });

  // Least recently used bitmaps are forgotten, like on the viewer: the
  // bitmap is sent again when the shape comes back.
  for (let i = 0; i < 70; i++) {
    const other = Buffer.alloc(4 * 4 * 4, i + 1);
    state = { serial: 100 + i, bgra: other, width: 4, height: 4, xhot: 0, yhot: 0 };
    await new Promise((r) => setTimeout(r, 12));
  }
  state = { serial: 300, bgra: img, width: 4, height: 4, xhot: 1, yhot: 2 };
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(sent.at(-1).id, custom.id);
  assert.ok(sent.at(-1).png, 'evicted bitmap sent again');

  // Resync: the current shape and the bitmap are sent again.
  state = { serial: 8, bgra: img, width: 4, height: 4, xhot: 1, yhot: 2 };
  await new Promise((r) => setTimeout(r, 30));
  const before = sent.length;
  tracker.resync();
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(sent.length, before + 1);
  assert.ok(sent.at(-1).png);

  // Probe returning null (secure desktop) keeps the previous shape.
  state = null;
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(sent.length, before + 1);
  tracker.close();
  assert.ok(probe.closed);
});

test('remote cursor (viewer): validates shapes and bitmaps', async () => {
  const { createRemoteCursor, HIDDEN_CURSOR } = await importRenderer('viewer/remote-cursor.js');
  const el = { style: { cursor: '' } };
  const c = createRemoteCursor(el);
  c.handle({ type: 'cursor', shape: 'text' });
  assert.equal(el.style.cursor, '', 'inactive: stylesheet cursor');
  c.setActive(true);
  assert.equal(el.style.cursor, 'text');
  c.handle({ type: 'cursor', shape: 'url(javascript:alert(1)), auto' });
  assert.equal(el.style.cursor, 'default');
  c.handle({ type: 'cursor', shape: 'none' });
  assert.equal(el.style.cursor, HIDDEN_CURSOR);

  const png = 'data:image/png;base64,iVBORw0KGgo=';
  c.handle({ type: 'cursor', shape: 'custom', id: 'abc', hot: [4, 6], scale: 2, png });
  assert.equal(el.style.cursor, `image-set(url("${png}") 2x) 2 3, default`);
  c.handle({ type: 'cursor', shape: 'text' });
  c.handle({ type: 'cursor', shape: 'custom', id: 'abc' });
  assert.ok(el.style.cursor.includes(png), 'cached bitmap reused');
  c.handle({ type: 'cursor', shape: 'custom', id: 'evil', png: 'data:image/png;base64,AAAA") , url("https://x' });
  assert.equal(el.style.cursor, 'default', 'invalid data URL rejected');
  c.handle({ type: 'cursor', shape: 'custom', id: 'unknown' });
  assert.equal(el.style.cursor, 'default');
  // LRU: a bitmap in use stays cached while 64 others come and go.
  for (let i = 0; i < 100; i++) {
    c.handle({ type: 'cursor', shape: 'custom', id: `f${i}`, png });
    c.handle({ type: 'cursor', shape: 'custom', id: 'abc' });
    assert.ok(el.style.cursor.startsWith('image-set'), `kept after ${i}`);
  }
  c.handle({ type: 'cursor', shape: 'custom', id: 'f0' });
  assert.equal(el.style.cursor, 'default', 'least recently used dropped');
  c.reset();
  assert.equal(el.style.cursor, '', 'reset: back to the stylesheet cursor until the host reports');
  c.handle({ type: 'cursor', shape: 'custom', id: 'abc' });
  assert.equal(el.style.cursor, 'default', 'cache cleared');
  c.setActive(false);
  assert.equal(el.style.cursor, '');
});

test('clipboard: limit counts UTF-8 bytes', () => {
  const written = [];
  const sync = new ClipboardSync({ clipboard: { readText: () => '', writeText: (t) => written.push(t) } });
  sync.setText('é'.repeat(150 * 1024)); // 300 KiB in UTF-8
  assert.equal(written.length, 0);
  sync.setText('a'.repeat(150 * 1024));
  assert.equal(written.length, 1);
});
