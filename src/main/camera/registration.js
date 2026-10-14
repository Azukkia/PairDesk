// Registration of the "PairDesk Camera" DirectShow filter (native/softcam)
// for the current Windows user, without administrator rights.
//
// The DLL shipped with the app is first copied to
// %LOCALAPPDATA%\PairDesk\camera\PairDeskCamera-<hash>.dll: applications
// using the webcam keep that file open, and an update of PairDesk must never
// have to overwrite a DLL in use. The copy is then registered under
// HKCU\Software\Classes, which Windows merges into HKEY_CLASSES_ROOT:
//
//   CLSID\{class id}                       "PairDesk Camera"
//     InprocServer32                       <dll>, ThreadingModel=Both
//   CLSID\{video input category}\Instance\{class id}
//     CLSID, FriendlyName, FilterData      (what regsvr32 would write)

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';

export const CAMERA_NAME = 'PairDesk Camera';
export const CAMERA_CLSID = '{DC55A64C-A76A-40C0-8494-4D5E79100E45}';
const VIDEO_INPUT_CATEGORY = '{860BB310-5D01-11D0-BD3B-00A0C911CE86}';
const MEDIATYPE_VIDEO = '{73646976-0000-0010-8000-00AA00389B71}';
const GUID_NULL = '{00000000-0000-0000-0000-000000000000}';
const MERIT_DO_NOT_USE = 0x200000;
const REG_PINFLAG_B_OUTPUT = 0x8;

const CLASSES = 'HKCU\\Software\\Classes';
export const KEYS = {
  clsid: `${CLASSES}\\CLSID\\${CAMERA_CLSID}`,
  server: `${CLASSES}\\CLSID\\${CAMERA_CLSID}\\InprocServer32`,
  instance: `${CLASSES}\\CLSID\\${VIDEO_INPUT_CATEGORY}\\Instance\\${CAMERA_CLSID}`,
};

/** A GUID as stored in memory (little-endian fields). */
export function guidBytes(guid) {
  const hex = guid.replace(/[{}-]/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) throw new Error(`bad GUID ${guid}`);
  const b = Buffer.from(hex, 'hex');
  return Buffer.concat([
    Buffer.from(b.subarray(0, 4)).reverse(),
    Buffer.from(b.subarray(4, 6)).reverse(),
    Buffer.from(b.subarray(6, 8)).reverse(),
    b.subarray(8),
  ]);
}

/**
 * The FilterData value IFilterMapper2::RegisterFilter writes for the filter
 * (REGFILTER2 version 2): merit, one output pin, media type Video/any. The
 * GUIDs follow the structures, referenced by their offset in the blob.
 */
export function filterData() {
  const head = Buffer.alloc(16);
  head.writeUInt32LE(2, 0); // version
  head.writeUInt32LE(MERIT_DO_NOT_USE, 4);
  head.writeUInt32LE(1, 8); // pins
  const pin = Buffer.alloc(24);
  pin.write('0pi3', 0, 'latin1');
  pin.writeUInt32LE(REG_PINFLAG_B_OUTPUT, 4);
  pin.writeUInt32LE(0, 8); // instances
  pin.writeUInt32LE(1, 12); // media types
  pin.writeUInt32LE(0, 16); // mediums
  pin.writeUInt32LE(0, 20); // no pin category
  const type = Buffer.alloc(16);
  const guids = head.length + pin.length + type.length;
  type.write('0ty3', 0, 'latin1');
  type.writeUInt32LE(guids, 8); // major type
  type.writeUInt32LE(guids + 16, 12); // minor type
  return Buffer.concat([head, pin, type, guidBytes(MEDIATYPE_VIDEO), guidBytes(GUID_NULL)]);
}

/** reg.exe command lines registering `dll`. */
export function registerCommands(dll) {
  return [
    ['add', KEYS.clsid, '/ve', '/d', CAMERA_NAME, '/f'],
    ['add', KEYS.server, '/ve', '/d', dll, '/f'],
    ['add', KEYS.server, '/v', 'ThreadingModel', '/d', 'Both', '/f'],
    ['add', KEYS.instance, '/v', 'CLSID', '/d', CAMERA_CLSID, '/f'],
    ['add', KEYS.instance, '/v', 'FriendlyName', '/d', CAMERA_NAME, '/f'],
    ['add', KEYS.instance, '/v', 'FilterData', '/t', 'REG_BINARY', '/d', filterData().toString('hex'), '/f'],
  ];
}

const reg = (args) => new Promise((resolve, reject) => {
  execFile('reg.exe', args, { windowsHide: true, timeout: 15_000 }, (err, stdout, stderr) => {
    if (err) reject(new Error(`reg ${args[0]} ${args[1]}: ${String(stderr || err.message).trim()}`));
    else resolve(stdout);
  });
});

/** The DLL currently registered for this user (null if none). */
export async function registeredDll() {
  try {
    const out = await reg(['query', KEYS.server, '/ve']);
    const m = /REG_(?:EXPAND_)?SZ\s+(.+)$/m.exec(out);
    if (!m) return null;
    await reg(['query', KEYS.instance, '/v', 'FriendlyName']);
    return m[1].trim();
  } catch {
    return null;
  }
}

/**
 * Copies `source` to `dir` under a name derived from its content (an update
 * with a new DLL gets a new file) and removes the older copies not in use.
 */
export function installCopy(source, dir) {
  const hash = crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex').slice(0, 12);
  const target = path.join(dir, `PairDeskCamera-${hash}.dll`);
  fs.mkdirSync(dir, { recursive: true });
  if (!fs.existsSync(target)) {
    const tmp = `${target}.${process.pid}.tmp`;
    fs.copyFileSync(source, tmp);
    fs.renameSync(tmp, target);
  }
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name);
    if (file === target || !/^PairDeskCamera-.*\.dll$/.test(name)) continue;
    try {
      fs.rmSync(file);
    } catch {
      /* still used by an application: next time */
    }
  }
  return target;
}

/** Makes sure "PairDesk Camera" is registered for this user with `source`. Returns the DLL to load. */
export async function ensureRegistered({ source, dir, log }) {
  const dll = installCopy(source, dir);
  if ((await registeredDll())?.toLowerCase() === dll.toLowerCase()) return dll;
  for (const args of registerCommands(dll)) await reg(args);
  log?.info(`[camera] "${CAMERA_NAME}" registered for this user (${dll})`);
  return dll;
}
