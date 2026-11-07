// Clipboard sync: rich content, images, copied files and their transfer.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ClipboardSync, richParts, MAX_TEXT_BYTES, MAX_IMAGE_BYTES, uriList, parseUriList, osFormat } from '../../src/main/clipboard.js';
import { ClipboardTransfers, expandPaths, safeRelative } from '../../src/main/clipboard-transfer.js';
import { dropFilesBuffer } from '../../src/main/clipboard-native.js';
import { fakeClipboard, FakeClipboardItem } from './fake-clipboard.js';
import { waitUntil } from './helpers.js';

const log = { info() {}, warn() {}, error() {} };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test('rich parts: text first, everything in one message', () => {
  assert.deepEqual(richParts({ text: 'a', html: '<b>a</b>', rtf: '' }), { text: 'a', html: '<b>a</b>' });
  const big = 'x'.repeat(MAX_TEXT_BYTES - 10);
  assert.deepEqual(Object.keys(richParts({ text: big, html: big, rtf: 'r' })), ['text', 'rtf']);
  assert.deepEqual(richParts({ text: 'x'.repeat(MAX_TEXT_BYTES + 1), html: '<i>ok</i>' }), { html: '<i>ok</i>' });
});

test('rich parts: measured as sent, JSON escapes included', () => {
  const escaped = '"\\\n'.repeat(30_000); // 90 KB, twice that in JSON
  const parts = richParts({ text: escaped, html: escaped, rtf: escaped });
  assert.deepEqual(Object.keys(parts), ['text']);
  assert.ok(Buffer.byteLength(JSON.stringify({ type: 'clipboard', cid: 'c'.repeat(12), ...parts })) < 256 * 1024);
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

test('clipboard sync: without a sequence number, another image is noticed and a written one not echoed', async (t) => {
  const board = fakeClipboard();
  const image = (data) => ({ text: '', html: '', rtf: '', png: Buffer.from(data), raw: {} });
  const sync = new ClipboardSync({ clipboard: board, ClipboardItem: FakeClipboardItem, intervalMs: 10, imageCacheMs: 30, log });
  t.after(() => sync.release());
  const seen = [];
  sync.on('change', (c) => seen.push(c.png?.toString()));
  sync.acquire();
  await wait(30);
  board.state = image('A');
  await waitUntil(() => seen.length === 1);
  // Same formats, no text: only the image differs.
  board.state = image('B');
  await waitUntil(() => seen.length === 2);
  assert.ok(await sync.write({ png: Buffer.from('C') }));
  await wait(120);
  assert.deepEqual(seen, ['A', 'B'], 'the partner\'s image is not sent back');
  board.state = image('D');
  await waitUntil(() => seen.length === 3);
  assert.equal(seen[2], 'D');
});

test('clipboard sync: a poll during a write does not send the written content back', async (t) => {
  const board = fakeClipboard({ text: 'start' });
  const write = board.write.bind(board);
  // The new content is visible a while before the write completes.
  board.write = async (items) => {
    await write(items);
    await wait(60);
  };
  const sync = new ClipboardSync({ clipboard: board, ClipboardItem: FakeClipboardItem, intervalMs: 5, log });
  t.after(() => sync.release());
  const seen = [];
  sync.on('change', (c) => seen.push(c.text));
  sync.acquire();
  await wait(20);
  assert.ok(await sync.write({ text: 'from partner' }));
  await wait(40);
  assert.deepEqual(seen, []);
  board.state = { ...board.state, text: 'copied here' };
  await waitUntil(() => seen.length === 1);
  assert.deepEqual(seen, ['copied here']);
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
  const received = [];
  for (const [i, e] of files.entries()) {
    const acc = receiver.accept(ctx, { cid: payload.cid, kind: 'file', rel: e.rel, n: 2 }, 'x');
    assert.ok(acc.directory.startsWith(path.join(root, 'r')));
    const full = path.join(acc.directory, acc.name);
    fs.writeFileSync(full, 'x');
    received.push(full);
    receiver.track(`t${i}`, ctx, payload.cid, acc);
  }
  assert.equal(await receiver.fileDone('t0', received[0]), null);
  const ready = await receiver.fileDone('t1', received[1]);
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
  const acc = receiver.accept(ctx, { cid: 'c1', kind: 'image', n: 1 }, 'clipboard.png', 3);
  assert.equal(receiver.accept(ctx, { cid: 'c1', kind: 'image', n: 1 }, 'clipboard.png', 3), null, 'one image per announcement');
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

test('clipboard transfers: older announcements stay valid, a file that shrank fails, empty folders fit in one message', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip4-'));
  const a = path.join(root, 'a.bin');
  fs.writeFileSync(a, Buffer.alloc(100, 1));
  const sender = new ClipboardTransfers({ clipboard: { write() {} }, log, stagingRoot: path.join(root, 's') });
  const first = await sender.announce({ files: [a] });
  // Another session starts: the same clipboard is announced to it.
  const second = await sender.announce({ files: [a] });
  assert.notEqual(first.cid, second.cid);
  assert.equal((await sender.list(first.cid)).length, 1, 'the first session can still paste');
  assert.equal((await sender.read(first.cid, 0, 0, 50)).length, 50);
  fs.writeFileSync(a, Buffer.alloc(10, 1));
  await assert.rejects(sender.read(first.cid, 0, 0, 50), /changed/);
  for (let i = 0; i < 10; i++) await sender.announce({ files: [a] });
  assert.equal(await sender.list(first.cid), null, 'only recent announcements are kept');

  const many = path.join(root, 'many');
  for (let i = 0; i < 3000; i++) fs.mkdirSync(path.join(many, `${'d'.repeat(80)}${i}`), { recursive: true });
  const box = await sender.announce({ files: [many] });
  const folders = (await sender.list(box.cid)).filter((e) => e.dir).map((e) => e.rel);
  assert.ok(folders.length > 1000 && folders.length < 3000);
  assert.ok(Buffer.byteLength(JSON.stringify({ type: 'clipboard-files', cid: box.cid, n: 0, folders })) < 256 * 1024);
  fs.rmSync(root, { recursive: true, force: true });
});

test('clipboard transfers: content arriving after a newer copy is not written', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip5-'));
  const written = [];
  const receiver = new ClipboardTransfers({ clipboard: { write: async (c) => { written.push(c); return true; } }, log, stagingRoot: root });
  const ctx = {};
  const receive = async (cid, rel, token) => {
    const acc = receiver.accept(ctx, { cid, kind: 'file', rel, n: 1 }, rel);
    const file = path.join(acc.directory, acc.name);
    fs.writeFileSync(file, rel);
    receiver.track(token, ctx, cid, acc);
    return file;
  };
  // An image announced, then something copied here before it arrives.
  receiver.remote(ctx, { cid: 'c1', text: 'caption', image: { size: 3 } });
  await wait(0);
  const acc = receiver.accept(ctx, { cid: 'c1', kind: 'image', n: 1 }, 'clipboard.png', 3);
  const image = path.join(acc.directory, acc.name);
  fs.writeFileSync(image, 'PNG');
  receiver.track('t1', ctx, 'c1', acc);
  receiver.localChanged();
  await receiver.fileDone('t1', image);
  assert.deepEqual(written, [{ text: 'caption' }], 'only the caption, written at once');
  assert.ok(!fs.existsSync(image));

  // Files fetched (leaving the viewer), then a local copy before they arrive.
  receiver.remote(ctx, { cid: 'c2', files: [{ name: 'a.txt', size: 1 }], total: 1 });
  assert.equal(await receiver.expect(ctx, 'c2', 1, []), null);
  const fa = await receive('c2', 'a.txt', 't2');
  receiver.localChanged();
  assert.equal(await receiver.fileDone('t2', fa), null);
  // Empty folders only.
  receiver.remote(ctx, { cid: 'c3', files: [{ name: 'vide', dir: true }], total: 0 });
  receiver.localChanged();
  assert.equal(await receiver.expect(ctx, 'c3', 0, ['vide']), null);
  // Newer content from another session.
  receiver.remote(ctx, { cid: 'c4', files: [{ name: 'b.txt', size: 1 }], total: 1 });
  const fb = await receive('c4', 'b.txt', 't4');
  receiver.remote({}, { cid: 'o1', text: 'newer' });
  assert.equal(await receiver.fileDone('t4', fb), null);
  await wait(0);
  assert.deepEqual(written, [{ text: 'caption' }, { text: 'newer' }]);
  // Nothing newer: written.
  receiver.remote(ctx, { cid: 'c5', files: [{ name: 'c.txt', size: 1 }], total: 1 });
  const fc = await receive('c5', 'c.txt', 't5');
  assert.deepEqual(await receiver.fileDone('t5', fc), { ready: 'c5' });
  assert.deepEqual(written.at(-1), { files: [fc] });
  fs.rmSync(root, { recursive: true, force: true });
});

test('clipboard transfers: files known by their final name, staging folders deleted once unused', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip6-'));
  const written = [];
  const receiver = new ClipboardTransfers({ clipboard: { write: async (c) => { written.push(c); return true; } }, log, stagingRoot: root });
  const ctx = {};
  receiver.remote(ctx, { cid: 'c1', files: [{ name: 'README', size: 6 }, { name: 'readme', size: 6 }], total: 12 });
  const finals = [];
  for (const [i, name] of ['README', 'readme'].entries()) {
    const acc = receiver.accept(ctx, { cid: 'c1', kind: 'file', rel: name, n: 2 }, name);
    // As on Windows: "readme" is taken by "README", the receiver saves "readme (1)".
    const final = path.join(acc.directory, i ? 'readme (1)' : acc.name);
    fs.writeFileSync(final, name);
    finals.push(final);
    receiver.track(`t${i}`, ctx, 'c1', acc);
  }
  await receiver.fileDone('t0', finals[0]);
  assert.deepEqual(await receiver.fileDone('t1', finals[1]), { ready: 'c1' });
  assert.deepEqual(written.at(-1).files, finals);
  const staged = path.dirname(finals[0]);
  const later = () => Date.now() + 3600_000;

  // Still in the clipboard after the session: kept.
  receiver.endSession(ctx);
  receiver.sweep(later());
  assert.ok(fs.existsSync(staged));
  // Replaced in the clipboard: deleted, but not right away (a paste may still be copying).
  receiver.localChanged();
  receiver.sweep();
  assert.ok(fs.existsSync(staged));
  receiver.sweep(later());
  assert.ok(!fs.existsSync(staged));
  // Files that never reached the clipboard: deleted some time after the session.
  receiver.remote(ctx, { cid: 'c2', files: [{ name: 'x', size: 1 }], total: 1 });
  const acc = receiver.accept(ctx, { cid: 'c2', kind: 'file', rel: 'x', n: 2 }, 'x');
  fs.writeFileSync(path.join(acc.directory, 'x'), 'x');
  receiver.endSession(ctx);
  receiver.sweep(later());
  assert.ok(!fs.existsSync(acc.directory));
  fs.rmSync(root, { recursive: true, force: true });
});

test('clipboard transfers: content that cannot be sent is reported', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-clip7-'));
  const sender = new ClipboardTransfers({ clipboard: { write() {} }, log, stagingRoot: path.join(root, 's') });
  assert.deepEqual(await sender.announce({ png: Buffer.alloc(MAX_IMAGE_BYTES + 1) }), { notice: 'image-too-big' });
  const long = await sender.announce({ text: 'x'.repeat(MAX_TEXT_BYTES + 1), html: '<b>x</b>' });
  assert.equal(long.notice, 'text-too-long');
  assert.equal(long.html, '<b>x</b>');
  assert.equal(long.text, undefined);
  assert.equal(await sender.announce({ text: '' }), null);
  // Folders nested deeper than a received path may be: the rest is still sent.
  const top = path.join(root, 'deep');
  const deep = path.join(top, ...Array(40).fill('d'));
  fs.mkdirSync(deep, { recursive: true });
  fs.writeFileSync(path.join(deep, 'f.txt'), 'x');
  fs.writeFileSync(path.join(top, 'top.txt'), 'x');
  const payload = await sender.announce({ files: [top] });
  assert.equal(payload.notice, 'too-many-files');
  const list = await sender.list(payload.cid);
  assert.deepEqual(list.map((e) => e.rel), ['deep/top.txt']);
  fs.rmSync(root, { recursive: true, force: true });
});
