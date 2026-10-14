// Native clipboard helpers that Electron does not offer on Windows: the
// clipboard sequence number and copied files (CF_HDROP). On Linux copied
// files are plain clipboard types (see clipboard.js).

import koffi from 'koffi';

let win32 = null;
function win32Api() {
  if (win32) return win32;
  const user32 = koffi.load('user32.dll');
  const kernel32 = koffi.load('kernel32.dll');
  const shell32 = koffi.load('shell32.dll');
  win32 = {
    GetClipboardSequenceNumber: user32.func('uint32_t __stdcall GetClipboardSequenceNumber()'),
    OpenClipboard: user32.func('int __stdcall OpenClipboard(void *hWnd)'),
    CloseClipboard: user32.func('int __stdcall CloseClipboard()'),
    EmptyClipboard: user32.func('int __stdcall EmptyClipboard()'),
    IsClipboardFormatAvailable: user32.func('int __stdcall IsClipboardFormatAvailable(uint32_t format)'),
    GetClipboardData: user32.func('void * __stdcall GetClipboardData(uint32_t format)'),
    SetClipboardData: user32.func('void * __stdcall SetClipboardData(uint32_t format, void *hMem)'),
    RegisterClipboardFormatW: user32.func('uint32_t __stdcall RegisterClipboardFormatW(const char16_t *name)'),
    GlobalAlloc: kernel32.func('void * __stdcall GlobalAlloc(uint32_t flags, uintptr_t bytes)'),
    GlobalLock: kernel32.func('void * __stdcall GlobalLock(void *hMem)'),
    GlobalUnlock: kernel32.func('int __stdcall GlobalUnlock(void *hMem)'),
    GlobalFree: kernel32.func('void * __stdcall GlobalFree(void *hMem)'),
    // `file` receives UTF-16 into the Buffer passed (or null to get the length).
    DragQueryFileW: shell32.func('uint32_t __stdcall DragQueryFileW(void *hDrop, uint32_t index, uint8_t *file, uint32_t size)'),
    RtlMoveMemory: kernel32.func('void __stdcall RtlMoveMemory(void *dest, const uint8_t *src, uintptr_t length)'),
  };
  return win32;
}

const CF_HDROP = 15;
const GMEM_MOVEABLE = 0x0002;
const DROPEFFECT_COPY = 1;

/** The CF_HDROP payload: DROPFILES header + double-NUL-terminated UTF-16 paths. */
export function dropFilesBuffer(paths) {
  const list = Buffer.from(`${paths.join('\0')}\0\0`, 'utf16le');
  const header = Buffer.alloc(20);
  header.writeUInt32LE(20, 0); // pFiles: offset of the list
  header.writeInt32LE(0, 4); // pt.x
  header.writeInt32LE(0, 8); // pt.y
  header.writeUInt32LE(0, 12); // fNC
  header.writeUInt32LE(1, 16); // fWide: UTF-16
  return Buffer.concat([header, list]);
}

/**
 * Windows: returns a function reading the clipboard sequence number (it
 * changes whenever the clipboard content changes), so polling can skip the
 * actual read. Elsewhere returns null.
 */
export function clipboardSequence(log) {
  if (process.platform !== 'win32') return null;
  try {
    const api = win32Api();
    return () => api.GetClipboardSequenceNumber();
  } catch (err) {
    log?.warn(`[clipboard] sequence number unavailable: ${err.message}`);
    return null;
  }
}

/** Windows: copied files of the system clipboard (null elsewhere). */
export function createClipboardFiles({ log }) {
  if (process.platform === 'win32') {
    let api;
    try {
      api = win32Api();
    } catch (err) {
      log?.warn(`[clipboard] files unavailable: ${err.message}`);
      return { read: () => null, write: () => false };
    }
    const preferredEffect = api.RegisterClipboardFormatW('Preferred DropEffect');
    const open = () => {
      // Another application may hold the clipboard for a few milliseconds.
      for (let i = 0; i < 10; i++) {
        if (api.OpenClipboard(null)) return true;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10);
      }
      return false;
    };
    const globalOf = (buf) => {
      const h = api.GlobalAlloc(GMEM_MOVEABLE, buf.length);
      if (!h) return null;
      const p = api.GlobalLock(h);
      api.RtlMoveMemory(p, buf, buf.length);
      api.GlobalUnlock(h);
      return h;
    };
    return {
      read() {
        if (!api.IsClipboardFormatAvailable(CF_HDROP) || !open()) return null;
        try {
          const h = api.GetClipboardData(CF_HDROP);
          if (!h) return null;
          const count = api.DragQueryFileW(h, 0xffffffff, null, 0);
          const files = [];
          for (let i = 0; i < Math.min(count, 1000); i++) {
            const len = api.DragQueryFileW(h, i, null, 0);
            const buf = Buffer.alloc((len + 1) * 2);
            api.DragQueryFileW(h, i, buf, len + 1);
            const file = buf.toString('utf16le', 0, len * 2);
            if (file) files.push(file);
          }
          return files;
        } finally {
          api.CloseClipboard();
        }
      },
      write(paths) {
        if (!paths.length || !open()) return false;
        try {
          api.EmptyClipboard();
          const drop = globalOf(dropFilesBuffer(paths));
          if (!drop || !api.SetClipboardData(CF_HDROP, drop)) return false;
          const effect = Buffer.alloc(4);
          effect.writeUInt32LE(DROPEFFECT_COPY, 0);
          const eff = globalOf(effect);
          if (eff) api.SetClipboardData(preferredEffect, eff);
          return true;
        } finally {
          api.CloseClipboard();
        }
      },
    };
  }
  return null;
}
