import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { KEYMAP, KEY_COMBOS } from '../../src/shared/keymap.js';
import { generateDeviceId, generatePassword, formatId, normalizeId, isValidId, PASSWORD_ALPHABET } from '../../src/shared/protocol.js';
import { createTranslator, resolveLanguage, STRINGS } from '../../src/shared/i18n.js';
import { sanitizeFileName, FileReceiver } from '../../src/main/files.js';
import { Settings } from '../../src/main/settings.js';
import { ClipboardSync } from '../../src/main/clipboard.js';
import { fakeClipboard, FakeClipboardItem } from './fake-clipboard.js';

test('keymap: scan codes and evdev codes are unique', () => {
  const entries = Object.entries(KEYMAP).filter(([code]) => !code.startsWith('OS'));
  const win = entries.map(([, k]) => k.win).filter((v) => v != null);
  const evdev = entries.map(([, k]) => k.evdev);
  assert.equal(new Set(win).size, win.length);
  assert.equal(new Set(evdev).size, evdev.length);
  for (const combo of Object.values(KEY_COMBOS)) for (const code of combo) assert.ok(KEYMAP[code], code);
  assert.equal(KEYMAP.KeyA.win, 0x1e);
  assert.equal(KEYMAP.ArrowUp.win, 0xe048);
  assert.equal(KEYMAP.KeyA.evdev + 8, 38);
});

test('protocol helpers', () => {
  for (let i = 0; i < 200; i++) {
    const id = generateDeviceId();
    assert.ok(isValidId(id), id);
    const pw = generatePassword(8);
    assert.equal(pw.length, 8);
    for (const c of pw) assert.ok(PASSWORD_ALPHABET.includes(c));
  }
  assert.equal(formatId('123456789'), '123 456 789');
  assert.equal(normalizeId(' 123-456 789 '), '123456789');
  assert.ok(!isValidId('012345678'));
  assert.ok(!isValidId('12345678'));
});

test('i18n: French and English define the same keys', () => {
  assert.deepEqual(Object.keys(STRINGS.fr).sort(), Object.keys(STRINGS.en).sort());
  assert.equal(resolveLanguage('auto', 'fr-FR'), 'fr');
  assert.equal(resolveLanguage('auto', 'de-DE'), 'en');
  assert.equal(createTranslator('fr')('update.available', { version: '2.0.0' }), 'La version 2.0.0 de PairDesk est disponible.');
});

test('received file names are sanitised', () => {
  assert.equal(sanitizeFileName('../../etc/passwd'), 'passwd');
  assert.equal(sanitizeFileName('C:\\Windows\\evil.exe'), 'evil.exe');
  assert.equal(sanitizeFileName('a<b>c:d"e|f?g*h.txt'), 'a_b_c_d_e_f_g_h.txt');
  assert.equal(sanitizeFileName('CON.txt'), '_CON.txt');
  assert.equal(sanitizeFileName('..'), 'fichier');
  assert.equal(sanitizeFileName('report. '), 'report');
  assert.ok(sanitizeFileName('x'.repeat(400) + '.pdf').endsWith('.pdf'));
});

test('FileReceiver writes, renames on completion and refuses overflow', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-files-'));
  const rx = new FileReceiver({ getDirectory: () => dir });
  const a = rx.begin('s1', 'hello.txt', 5);
  assert.ok(rx.write('s1', a.token, Buffer.from('hel')));
  assert.ok(!rx.write('other-session', a.token, Buffer.from('x')));
  assert.ok(rx.write('s1', a.token, Buffer.from('lo')));
  const final = await rx.end('s1', a.token);
  assert.equal(fs.readFileSync(final, 'utf8'), 'hello');
  const b = rx.begin('s1', 'hello.txt', 2);
  assert.equal(b.name, 'hello (1).txt');
  assert.ok(!rx.write('s1', b.token, Buffer.from('too long')));
  await new Promise((r) => setTimeout(r, 50));
  assert.ok(!fs.existsSync(path.join(dir, 'hello (1).txt.pdpart')));
});

test('Settings: validation, secrets and recents', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-settings-'));
  const file = path.join(dir, 'settings.json');
  const crypter = { available: () => true, encrypt: (s) => Buffer.from(`enc:${s}`), decrypt: (b) => b.toString().slice(4) };
  const s = new Settings(file, crypter);
  assert.ok(isValidId(s.get('deviceId')));
  const applied = s.update({ language: 'fr', passwordLength: 99, serverUrl: 'http://bad', deviceId: '111111111', allowControl: false });
  assert.deepEqual(applied, { language: 'fr', allowControl: false });
  assert.notEqual(s.get('deviceId'), '111111111');
  const prs = Buffer.from('0123456789abcdef0123456789abcdef');
  s.touchRecent('222222222', { name: 'PC A', prs });
  s.touchRecent('333333333', { name: 'PC B', prs: null });
  s.touchRecent('222222222', {});
  assert.deepEqual(s.publicRecents().map((r) => [r.id, r.hasPassword, r.count]), [['222222222', true, 2], ['333333333', false, 1]]);
  assert.deepEqual(s.savedPrs('222222222'), prs);
  assert.equal(s.data.recents[0].prs.enc, 'os');
  s.saveNow();
  const again = new Settings(file, crypter);
  assert.equal(again.get('deviceId'), s.get('deviceId'));
  assert.equal(again.get('language'), 'fr');
  assert.deepEqual(again.savedPrs('222222222'), prs);
});

test('ClipboardSync reports local changes and does not echo remote text', async () => {
  const board = fakeClipboard({ text: 'initial' });
  const sync = new ClipboardSync({ clipboard: board, ClipboardItem: FakeClipboardItem, intervalMs: 10 });
  const seen = [];
  sync.on('change', (c) => seen.push(c.text));
  sync.acquire();
  await new Promise((r) => setTimeout(r, 30));
  board.state.text = 'copied locally';
  await new Promise((r) => setTimeout(r, 40));
  await sync.setText('from partner');
  await new Promise((r) => setTimeout(r, 40));
  sync.release();
  assert.deepEqual(seen, ['copied locally']);
  assert.equal(board.state.text, 'from partner');
  assert.ok(sync.timer === null);
});
