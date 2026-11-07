// File transfers of the WebRTC session (src/renderer/common/rtc.js), over
// in-memory data channels.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { api, connectedPair, FakePeerConnection, wait } from './renderer-env.js';

const { RtcSession } = await import('../../src/renderer/common/rtc.js');

function recorder() {
  const updates = [];
  return { updates, update: (u) => updates.push(u), last: () => Object.assign({}, ...updates) };
}

/** Receiver side: every offer accepted, files:end answers like the main process. */
function receive(b, { res = {}, end = { ok: true, path: '/downloads/f' } } = {}) {
  const views = [];
  let n = 0;
  b.acceptFile = async ({ name }) => {
    const view = recorder();
    views.push(view);
    return { ok: true, token: `t${n++}`, name, view, ...res };
  };
  api.handlers['files:end'] = async () => end;
  return views;
}

const fileChannels = (s) => s.pc.created.filter((c) => c.label.startsWith('file:'));

test('rtc: file channels are closed once done, so a connection can carry any number of files', async (t) => {
  FakePeerConnection.maxChannels = 4;
  t.after(() => { FakePeerConnection.maxChannels = Infinity; });
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  receive(b);
  for (let i = 0; i < 12; i++) {
    const view = recorder();
    assert.equal(await a.sendFile(new File([`content ${i}`], `f${i}.txt`), view), true, `file ${i}`);
    assert.equal(view.last().status, 'sent');
  }
  await wait(5);
  assert.equal(fileChannels(a).length, 12);
  assert.ok(fileChannels(a).every((c) => c.readyState === 'closed' && c.peer.readyState === 'closed'));
});

test('rtc: no data channel left: the transfer fails, the receiver gives up', async (t) => {
  FakePeerConnection.maxChannels = 0;
  t.after(() => { FakePeerConnection.maxChannels = Infinity; });
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  const views = receive(b);
  api.sent.length = 0;
  const view = recorder();
  assert.equal(await a.sendFile(new File(['abc'], 'a.txt'), view), false);
  assert.equal(view.last().status, 'failed');
  await wait(5);
  assert.deepEqual(api.sent.filter(([ch]) => ch === 'files:abort'), [['files:abort', 't0']], 'file-cancel reached the receiver');
  assert.equal(views[0].last().status, 'failed');
  assert.equal(b.incomingFiles.size, 0);
});

test('rtc: a file that shrank while being sent fails instead of looping', async (t) => {
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  receive(b);
  let reads = 0;
  // 40 KB announced, but nothing left to read after the first chunk.
  const file = {
    name: 'shrunk.bin',
    size: 40_000,
    slice: (start, end) => ({ arrayBuffer: async () => (reads++, new ArrayBuffer(start === 0 ? end - start : 0)) }),
  };
  const view = recorder();
  assert.equal(await a.sendFile(file, view), false);
  assert.equal(view.last().status, 'failed');
  assert.equal(reads, 2);
  await wait(5);
  assert.ok(fileChannels(a).every((c) => c.readyState === 'closed'));
});

test('rtc: an offer that cannot be accepted (error) is refused at once', async (t) => {
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  b.acceptFile = async () => {
    throw new Error('EEXIST: file already exists');
  };
  const view = recorder();
  const started = Date.now();
  assert.equal(await a.sendFile(new File(['x'], 'A'), view), false);
  assert.equal(view.last().status, 'rejected');
  assert.ok(Date.now() - started < 1000);
});

test('rtc: bytes beyond the announced size are ignored', async (t) => {
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  receive(b);
  api.sent.length = 0;
  api.invoked.length = 0;
  a.sendControl({ type: 'file-offer', fid: 'abcd', name: 'four.bin', size: 4 });
  await wait(5);
  assert.ok(b.incomingFiles.has('abcd'));
  const dc = a.pc.createDataChannel('file:abcd');
  await wait(5);
  dc.send(new Uint8Array([1, 2, 3, 4]).buffer);
  dc.send(new Uint8Array([5, 6, 7, 8]).buffer);
  await wait(10);
  const chunks = api.sent.filter(([ch]) => ch === 'files:chunk');
  assert.equal(chunks.length, 1);
  assert.deepEqual([...chunks[0][2]], [1, 2, 3, 4]);
  assert.equal(api.invoked.filter(([ch]) => ch === 'files:end').length, 1);
  assert.equal(dc.readyState, 'closed', 'the receiver closed the channel');
});

test('rtc: received clipboard and dropped files offer no "show" button', async (t) => {
  const { a, b } = connectedPair(RtcSession);
  t.after(() => { a.close(); b.close(); });
  let views = receive(b);
  assert.equal(await a.sendFile(new File(['x'], 'plain.txt'), recorder()), true);
  assert.equal(views[0].last().path, '/downloads/f');
  views = receive(b, { res: { clip: 'file' }, end: { ok: true, path: '/tmp/staging/f' } });
  assert.equal(await a.sendFile(new File(['x'], 'copied.txt'), recorder()), true);
  assert.equal(views[0].last().status, 'received');
  assert.equal(views[0].last().path, undefined);
  views = receive(b, { res: { dropped: true }, end: { ok: true, path: '/tmp/staging/d' } });
  assert.equal(await a.sendFile(new File(['x'], 'dropped.txt'), recorder()), true);
  assert.equal(views[0].last().path, undefined);
});
