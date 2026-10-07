// Current cursor shape on Windows (user32 GetCursorInfo), called with koffi.
// Runs in the host main process. GetCursorInfo reports the *global* cursor
// (whatever window shows it), and standard cursors are shared system handles,
// so comparing hCursor with LoadCursorW(NULL, IDC_*) identifies the shape.

import koffi from 'koffi';
import { WIN_IDC_TO_CSS } from '../../shared/cursor-shapes.js';

const CURSOR_SHOWING = 0x1;
const CURSOR_SUPPRESSED = 0x2; // Windows 8+: hidden because the user uses touch/pen

let types = null;
function declareTypes() {
  types ??= {
    // typedef struct { DWORD cbSize; DWORD flags; HCURSOR hCursor; POINT ptScreenPos; } CURSORINFO;
    // x64: cbSize 0, flags 4, hCursor 8 (pointer, 8 bytes), ptScreenPos 16 (2 × LONG = 2 × int32) → 24 bytes.
    // LONG is 32-bit on Windows (LLP64): int32_t avoids any 'long' ambiguity.
    CURSORINFO: koffi.struct('PD_CURSORINFO', {
      cbSize: 'uint32_t',
      flags: 'uint32_t',
      hCursor: 'uintptr_t', // HCURSOR compared by value: a pointer-sized integer
      ptScreenPos: koffi.struct('PD_CURSORINFO_POINT', { x: 'int32_t', y: 'int32_t' }),
    }),
  };
  return types;
}

/**
 * Returns a probe: probe.read() → { handle, shape, hidden, x, y } | null.
 * `shape` is a CSS keyword: a known system cursor, 'none' when the cursor is
 * hidden, or null for an application-specific cursor (caller falls back to
 * 'default'). Returns null when GetCursorInfo fails (secure desktop, UAC).
 * `user32` can be injected for tests.
 */
export function createWin32CursorProbe({ user32 = koffi.load('user32.dll') } = {}) {
  const { CURSORINFO } = declareTypes();
  const GetCursorInfo = user32.func('int __stdcall GetCursorInfo(_Inout_ PD_CURSORINFO *pci)');
  // HCURSOR LoadCursorW(HINSTANCE hInstance, LPCWSTR lpCursorName). The name is
  // MAKEINTRESOURCEW(id), i.e. the integer id in a pointer-sized slot: declaring
  // both parameters (and the result) as uintptr_t passes it exactly like the
  // C macro does (same register / stack slot as a pointer on x64 and x86).
  const LoadCursorW = user32.func('uintptr_t __stdcall LoadCursorW(uintptr_t hInstance, uintptr_t lpCursorName)');
  const CB_SIZE = koffi.sizeof(CURSORINFO);

  let byHandle = new Map();
  function loadSystemCursors() {
    byHandle = new Map();
    for (const [id, css] of Object.entries(WIN_IDC_TO_CSS)) {
      const h = LoadCursorW(0, Number(id)); // NULL instance = system cursor; 0 if unknown on this Windows version
      // String keys: koffi returns a BigInt instead of a Number for values above 2^53.
      if (h) byHandle.set(String(h), css);
    }
  }
  loadSystemCursors();

  const info = { cbSize: CB_SIZE, flags: 0, hCursor: 0, ptScreenPos: { x: 0, y: 0 } };
  const unknown = new Set();

  return {
    systemCursorCount: () => byHandle.size,
    read() {
      info.cbSize = CB_SIZE; // _Inout_: cbSize must be set on input or the call fails
      if (!GetCursorInfo(info)) return null;
      const handle = String(info.hCursor);
      const hidden = !(info.flags & CURSOR_SHOWING) && !(info.flags & CURSOR_SUPPRESSED);
      let shape = hidden || handle === '0' ? 'none' : byHandle.get(handle) ?? null;
      if (shape === null && !unknown.has(handle)) {
        // The cursor scheme may have changed (Settings > Mouse pointer): reload once per new handle.
        if (unknown.size > 256) unknown.clear();
        unknown.add(handle);
        loadSystemCursors();
        shape = byHandle.get(handle) ?? null;
      }
      return { handle, shape, hidden, flags: info.flags, x: info.ptScreenPos.x, y: info.ptScreenPos.y };
    },
    close() {},
  };
}
