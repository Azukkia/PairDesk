// Clipboard exchange, renderer side (src/renderer/common/clipboard-bridge.js).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { api, wait } from './renderer-env.js';

const { ClipboardBridge } = await import('../../src/renderer/common/clipboard-bridge.js');

function setup({ version = '1.2.0', files = true, rtc: overrides = {} } = {}) {
  const sent = [];
  const notices = [];
  const rtc = {
    sendControl: (msg) => {
      sent.push(msg);
      return true;
    },
    sendFile: async () => true,
    ...overrides,
  };
  const clip = new ClipboardBridge({
    rtc: () => rtc, peerVersion: () => version, enabled: () => true, files: () => files, notify: (key) => notices.push(key),
  });
  return { clip, rtc, sent, notices };
}

const filesPayload = (cid) => ({ cid, files: [{ name: 'a.txt', size: 3, dir: false }], total: 3 });

test('clipboard bridge: a transfer that throws fails the push instead of blocking it', async () => {
  const { clip } = setup({ rtc: { sendFile: async () => { throw new Error('no stream id available'); } } });
  api.handlers['clipboard:list'] = async () => [{ id: 0, rel: 'a.txt', size: 3, dir: false }, { id: 1, rel: 'b.txt', size: 3, dir: false }];
  clip.local(filesPayload('c1'));
  assert.equal(clip.undelivered?.cid, 'c1');
  assert.equal(await clip.push(), false);
  assert.ok(clip.failed.has('c1'));
  assert.equal(clip.undelivered, null, 'not retried on every Ctrl+V');
  // The main process refusing the list fails it as well.
  api.handlers['clipboard:list'] = async () => {
    throw new Error('forbidden');
  };
  clip.local(filesPayload('c2'));
  assert.equal(await clip.push(), false);
});

test('clipboard bridge: a new connection forgets both sides\' announcements and running pushes', async () => {
  const { clip } = setup();
  api.handlers['clipboard:list'] = async () => [{ id: 0, rel: 'a.txt', size: 3, dir: false }];
  clip.local(filesPayload('c1'));
  clip.onControl({ type: 'clipboard', cid: 'p1', files: [{ name: 'b', size: 1 }], total: 1 });
  const push = clip.push(); // never acknowledged by the partner
  await wait(5);
  clip.reset();
  assert.equal(clip.outbox, null);
  assert.equal(clip.inbox, null);
  const started = Date.now();
  assert.equal(await push, false);
  assert.ok(Date.now() - started < 1000, 'the old push does not wait for its timeout');
});

test('clipboard bridge: stale announcements are dropped', async () => {
  const { clip, sent } = setup();
  // Nothing in the clipboard at the start of a connection: nothing to push.
  clip.outbox = { cid: 'old', files: [], total: 0 };
  api.handlers['clipboard:current'] = async () => null;
  await clip.sendCurrent();
  assert.equal(clip.outbox, null);

  // A local copy: the partner's files are no longer fetched into our clipboard.
  clip.onControl({ type: 'clipboard', cid: 'p1', files: [{ name: 'b', size: 1 }], total: 1 });
  assert.equal(clip.inbox.cid, 'p1');
  clip.local({ cid: 'c2', text: 'local' });
  assert.equal(clip.inbox, null);
  assert.equal(clip.fetch(), false);

  // Copied files without file transfers in this session: nothing announced, nothing pushed.
  const noFiles = setup({ files: false });
  noFiles.clip.local(filesPayload('c3'));
  assert.equal(noFiles.clip.outbox, null);
  assert.equal(noFiles.sent.length, 0);

  // A change that could not be announced (not connected) makes ours stale too.
  clip.local(filesPayload('c4'));
  clip.onControl({ type: 'clipboard', cid: 'p2', files: [{ name: 'b', size: 1 }], total: 1 });
  clip.local(filesPayload('c5'));
  clip.localChanged();
  assert.equal(clip.outbox, null);
  assert.equal(clip.inbox, null);
  assert.ok(sent.length > 0);
});

test('clipboard bridge: too big for one message: the plain text alone; notices are shown', () => {
  const { clip, sent, notices } = setup({ rtc: { sendControl: (msg) => (sent.push(msg), !msg.html) } });
  clip.local({ cid: 'c1', text: 'hello', html: '<b>hello</b>' });
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1], { type: 'clipboard', cid: 'c1', text: 'hello' });

  clip.local({ notice: 'image-too-big' });
  assert.deepEqual(notices, ['clipboard.imageTooBig']);
  assert.equal(sent.length, 2, 'nothing else to send');
});
