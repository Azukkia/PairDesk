// "PairDesk Camera" virtual webcam: registration data and frame conversion.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { guidBytes, filterData, registerCommands, installCopy, KEYS, CAMERA_CLSID } from '../../src/main/camera/registration.js';
import { rgbaToBgr, fillBgr } from '../../src/main/camera/frames.js';

test('GUIDs are laid out like in memory', () => {
  // MEDIATYPE_Video = FOURCC 'vids'
  assert.equal(guidBytes('{73646976-0000-0010-8000-00AA00389B71}').toString('hex'), '76696473000010008000' + '00aa00389b71');
  assert.throws(() => guidBytes('{nope}'));
});

test('FilterData matches what IFilterMapper2 writes for one output video pin', () => {
  const d = filterData();
  assert.equal(d.length, 16 + 24 + 16 + 32);
  assert.deepEqual([d.readUInt32LE(0), d.readUInt32LE(4), d.readUInt32LE(8)], [2, 0x200000, 1]);
  assert.equal(d.toString('latin1', 16, 20), '0pi3');
  assert.equal(d.readUInt32LE(20), 0x8); // REG_PINFLAG_B_OUTPUT
  assert.equal(d.readUInt32LE(28), 1); // one media type
  assert.equal(d.toString('latin1', 40, 44), '0ty3');
  const major = d.readUInt32LE(48);
  const minor = d.readUInt32LE(52);
  assert.equal(d.subarray(major, major + 4).toString('latin1'), 'vids');
  assert.ok(d.subarray(minor, minor + 16).equals(Buffer.alloc(16)), 'any subtype');
});

test('registration only touches the current user', () => {
  const cmds = registerCommands('C:\\Users\\Me\\AppData\\Local\\PairDesk\\camera\\PairDeskCamera-abc.dll');
  for (const args of cmds) {
    assert.equal(args[0], 'add');
    assert.match(args[1], /^HKCU\\Software\\Classes\\CLSID\\/);
    assert.equal(args.at(-1), '/f');
  }
  assert.ok(cmds.some((a) => a[1] === KEYS.server && a.includes('Both')));
  assert.ok(cmds.some((a) => a[1] === KEYS.instance && a.includes('FriendlyName') && a.includes('PairDesk Camera')));
  assert.ok(KEYS.instance.endsWith(CAMERA_CLSID));
});

test('the DLL is copied under a name derived from its content; old copies go', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-cam-'));
  const src = path.join(dir, 'src.dll');
  fs.writeFileSync(src, 'v1');
  const target = path.join(dir, 'camera');
  const a = installCopy(src, target);
  assert.equal(installCopy(src, target), a, 'same content, same file');
  fs.writeFileSync(src, 'v2');
  const b = installCopy(src, target);
  assert.notEqual(a, b);
  assert.deepEqual(fs.readdirSync(target), [path.basename(b)]);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('frames: RGBA to BGR24, and a plain colour', () => {
  const out = Buffer.alloc(6);
  rgbaToBgr(new Uint8Array([1, 2, 3, 255, 10, 20, 30, 255]), out);
  assert.deepEqual([...out], [3, 2, 1, 30, 20, 10]);
  fillBgr(out, 0x112233);
  assert.deepEqual([...out], [0x33, 0x22, 0x11, 0x33, 0x22, 0x11]);
});
