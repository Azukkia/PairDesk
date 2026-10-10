// Clipboard sync: rich content, images, copied files and their transfer.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ClipboardSync, richParts, MAX_TEXT_BYTES, uriList, parseUriList, osFormat } from '../../src/main/clipboard.js';
import { ClipboardTransfers, expandPaths, safeRelative } from '../../src/main/clipboard-transfer.js';
import { dropFilesBuffer } from '../../src/main/clipboard-native.js';
import { fakeClipboard, FakeClipboardItem } from './fake-clipboard.js';

const log = { info() {}, warn() {}, error() {} };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test('rich parts: text first, everything in one message', () => {
  assert.deepEqual(richParts({ text: 'a', html: '<b>a</b>', rtf: '' }), { text: 'a', html: '<b>a</b>' });
  const big = 'x'.repeat(MAX_TEXT_BYTES - 10);
  assert.deepEqual(Object.keys(richParts({ text: big, html: big, rtf: 'r' })), ['text', 'rtf']);
  assert.deepEqual(richParts({ text: 'x'.repeat(MAX_TEXT_BYTES + 1), html: '<i>ok</i>' }), { html: '<i>ok</i>' });
});

test('clipboard sync: text, html, image and files, without echo', async (t) => {
  const board = fakeClipboard({ text: 'start' });
  let copied = null;
  const files = { read: () => copied, write: (paths) => { copied = paths; return true; } };
  const sync = new ClipboardSync({ clipboard: board, ClipboardItem: FakeClipboardItem, files, intervalMs: 10, log });
  t.after(() => sync.release()); // a failed assertion must not leave the poller running
  const seen = [];
  sync.on('change', (c) => seen.push(c));
  sync.acquire();
  await wait(30);
  assert.equal(seen.length, 0, 'content present at start is not a change');

  board.state = { text: 'hello', html: '<p>hello</p>', rtf: '', png: null, raw: {} };
  await wait(40);
  assert.equal(seen.length, 1);
  assert.equal(seen[0].html, '<p>hello</p>');

  board.state = { text: '', html: '', rtf: '', png: Buffer.from('PNGDATA'), raw: {} };
  await wait(40);
  assert.equal(seen.length, 2);
  assert.equal(seen[1].png.toString(), 'PNGDATA');

  copied = ['/tmp/a.txt', '/tmp/dossier'];
  await wait(40);
  assert.deepEqual(seen[2], { files: ['/tmp/a.txt', '/tmp/dossier'] });

  // Written from the partner: no change event (no echo).
  copied = null;
  assert.ok(await sync.write({ text: 'from partner', html: '<b>from partner</b>', png: Buffer.from('IMG') }));
  assert.equal(board.state.png.toString(), 'IMG');
  assert.equal(board.state.html, '<b>from partner</b>');
  await wait(40);
  assert.ok(await sync.write({ files: ['/tmp/x'] }));
  await wait(40);
  assert.ok(await sync.setText('plain'));
  assert.equal(await board.readText(), 'plain');
  await wait(40);
  sync.release();
  assert.equal(seen.length, 3);
  assert.deepEqual(copied, ['/tmp/x']);
});

// Linux only: Windows reads copied files natively (CF_HDROP), and these are POSIX paths.
test('clipboard sync: copied files without a native backend (Linux)', { skip: process.platform === 'win32' }, async (t) => {
  const board = fakeClipboard();
  const sync = new ClipboardSync({ clipboard: board, ClipboardItem: FakeClipboardItem, intervalMs: 10, log });
  t.after(() => sync.release());
  const seen = [];
  sync.on('change', (c) => seen.push(c));
  sync.acquire();
  await wait(30);
  // Copied in a file manager: text/uri-list.
  board.state = { text: '', html: '', rtf: '', png: null, raw: { 'text/uri-list': 'file:///home/me/a%20b.txt\r\nfile:///home/me/d' } };
  await wait(40);
  assert.deepEqual(seen, [{ files: ['/home/me/a b.txt', '/home/me/d'] }]);
  // Written for the file manager: both formats, no echo.
  assert.ok(await sync.write({ files: ['/tmp/é t.txt'] }));
  assert.equal(board.state.raw['text/uri-list'], 'file:///tmp/%C3%A9%20t.txt');
  assert.equal(board.state.raw['x-special/gnome-copied-files'], 'copy\nfile:///tmp/%C3%A9%20t.txt');
  assert.deepEqual(await sync.read(), { files: ['/tmp/é t.txt'] });
  await wait(40);
  sync.release();
  assert.equal(seen.length, 1);
  assert.equal(osFormat('text/uri-list'), 'electron application/osclipboard;format="text/uri-list"');
});

test('native helpers: CF_HDROP buffer and uri lists', () => {
  const buf = dropFilesBuffer(['C:\\a.txt', 'D:\\dossier é']);
  assert.equal(buf.readUInt32LE(0), 20);
  assert.equal(buf.readUInt32LE(16), 1);
  assert.equal(buf.subarray(20).toString('utf16le'), 'C:\\a.txt\0D:\\dossier é\0\0');
  const win = process.platform === 'win32';
  const dir = win ? 'C:\\me\\' : '/home/me/';
  const url = win ? 'file:///C:/me/' : 'file:///home/me/';
  const list = uriList([`${dir}a b.txt`, `${dir}été`]);
  assert.deepEqual(list, [`${url}a%20b.txt`, `${url}%C3%A9t%C3%A9`]);
  assert.deepEqual(parseUriList(`${list.join('\r\n')}\r\n# comment\r\nhttp://x`), [`${dir}a b.txt`, `${dir}été`]);
  assert.deepEqual(parseUriList(`copy\n${list[0]}`), [`${dir}a b.txt`]);
});

test('relative names are made safe', () => {
  assert.equal(safeRelative('dossier/sous/fichier.txt'), path.join('dossier', 'sous', 'fichier.txt'));
  assert.equal(safeRelative('../../etc/passwd'), path.join('etc', 'passwd'));
  assert.equal(safeRelative('a\\b<c>.txt'), path.join('a', 'b_c_.txt'));
  assert.equal(safeRelative('..'), null);
  assert.equal(safeRelative(''), null);
});

test('clipboard transfers: copied files and folders reach the other clipboard', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip-'));
  // The copying side.
  const src = path.join(root, 'src');
  fs.mkdirSync(path.join(src, 'dossier', 'vide'), { recursive: true });
  fs.writeFileSync(path.join(src, 'note.txt'), 'note');
  fs.writeFileSync(path.join(src, 'dossier', 'photo.jpg'), Buffer.alloc(1000, 7));
  const sender = new ClipboardTransfers({ clipboard: { write() {} }, log, stagingRoot: path.join(root, 's') });
  const payload = await sender.announce({ files: [path.join(src, 'note.txt'), path.join(src, 'dossier')] });
  assert.deepEqual(payload.files, [{ name: 'note.txt', size: 4, dir: false }, { name: 'dossier', size: null, dir: true }]);
  assert.equal(payload.total, 1004);
  const list = await sender.list(payload.cid);
  assert.deepEqual(list.map((e) => [e.rel, e.size, e.dir]), [
    ['note.txt', 4, false], ['dossier/photo.jpg', 1000, false], ['dossier/vide/', 0, true],
  ]);
  assert.equal(await sender.list('other'), null);
  const chunk = await sender.read(payload.cid, list[1].id, 10, 20);
  assert.equal(chunk.length, 10);
  await assert.rejects(sender.read(payload.cid, 99, 0, 1));

  // The receiving side.
  const written = [];
  const receiver = new ClipboardTransfers({ clipboard: { write: async (c) => { written.push(c); return true; } }, log, stagingRoot: path.join(root, 'r') });
  const ctx = {};
  const info = receiver.remote(ctx, payload);
  assert.deepEqual(info.files.map((f) => [f.name, f.dir]), [['note.txt', false], ['dossier', true]]);
  assert.equal(info.total, 1004);
  assert.equal(written.length, 0, 'files are only written once received');
  assert.equal(await receiver.expect(ctx, payload.cid, 2, ['dossier/vide']), null);
  const files = list.filter((e) => !e.dir);
  for (const [i, e] of files.entries()) {
    const acc = receiver.accept(ctx, { cid: payload.cid, kind: 'file', rel: e.rel, n: 2 }, 'x');
    assert.ok(acc.directory.startsWith(path.join(root, 'r')));
    const full = path.join(acc.directory, acc.name);
    fs.writeFileSync(full, 'x');
    receiver.track(`t${i}`, ctx, payload.cid, acc);
  }
  assert.equal(await receiver.fileDone('t0', path.join(root, 'r', 'nope')), null);
  const ready = await receiver.fileDone('t1', 'done');
  assert.deepEqual(ready, { ready: payload.cid });
  const tops = written.at(-1).files.map((p) => path.basename(p)).sort();
  assert.deepEqual(tops, ['dossier', 'note.txt']);
  assert.ok(fs.existsSync(path.join(path.dirname(written.at(-1).files[0]), 'dossier', 'vide')));

  // Unknown or stale announcements are refused.
  assert.equal(receiver.accept(ctx, { cid: 'stale', kind: 'file', rel: 'a', n: 1 }, 'a'), null);
  assert.equal(receiver.accept({}, { cid: payload.cid, kind: 'file', rel: 'a', n: 1 }, 'a'), null);
  fs.rmSync(root, { recursive: true, force: true });
});

test('clipboard transfers: text now, image when it arrives, legacy text', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip2-'));
  const written = [];
  const texts = [];
  const receiver = new ClipboardTransfers({
    clipboard: { write: async (c) => { written.push(c); return true; }, setText: async (t) => texts.push(t) },
    log,
    stagingRoot: root,
  });
  const ctx = {};
  receiver.remote(ctx, 'old style text');
  await wait(0);
  assert.deepEqual(texts, ['old style text']);
  receiver.remote(ctx, { cid: 'c1', text: 'caption', html: '<img>', image: { size: 3 } });
  await wait(0);
  assert.deepEqual(written.at(-1), { text: 'caption', html: '<img>' });
  const acc = receiver.accept(ctx, { cid: 'c1', kind: 'image', n: 1 }, 'clipboard.png');
  const file = path.join(acc.directory, acc.name);
  fs.writeFileSync(file, 'PNG');
  receiver.track('t', ctx, 'c1', acc);
  await receiver.fileDone('t', file);
  assert.equal(written.at(-1).png.toString(), 'PNG');
  assert.equal(written.at(-1).text, 'caption');
  assert.ok(!fs.existsSync(file), 'image file removed once in the clipboard');
  // Nothing to receive: empty folders only.
  receiver.remote(ctx, { cid: 'c2', files: [{ name: 'vide', dir: true }], total: 0 });
  assert.deepEqual(await receiver.expect(ctx, 'c2', 0, ['vide']), { ready: 'c2' });
  fs.rmSync(root, { recursive: true, force: true });
});

test('folder expansion is bounded', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip3-'));
  for (let i = 0; i < 20; i++) fs.writeFileSync(path.join(root, `f${i}`), 'x');
  const { entries, truncated } = await expandPaths([root], { maxEntries: 5 });
  assert.equal(entries.length, 5);
  assert.ok(truncated);
  fs.rmSync(root, { recursive: true, force: true });
});
