// Screen capture on Windows with DXGI Desktop Duplication
// (native/capture/capture.cpp → PairDeskCapture.dll), called with koffi from
// the capture helper process. No cursor in the picture, and a new picture
// only when the screen changed.

import koffi from 'koffi';

// Above this size the DLL halves the picture: a 4K screen would otherwise
// mean 33 MB to move per picture (the encoder scales it down anyway).
const HALVE_ABOVE = 2560 * 1600;

const cstr = (buf) => {
  const end = buf.indexOf(0);
  return buf.toString('utf8', 0, end < 0 ? buf.length : end);
};

/**
 * Opens the display at `rect` (physical desktop pixels). Returns
 * { width, height, stride, format, grabAsync(timeoutMs) → Promise<Buffer | null>, close() }:
 * grabAsync waits (off the JavaScript thread) up to timeoutMs for a change
 * and resolves the new picture (valid until the next call) or null.
 */
export function openWin32Capture(rect, { dll } = {}) {
  if (!dll) throw new Error('PairDeskCapture.dll not found');
  const lib = koffi.load(dll);
  const pdcOpen = lib.func('void * __cdecl pdcOpen(int left, int top, int width, int height, int halve, uint8_t *err, int errLen)');
  const pdcWidth = lib.func('int __cdecl pdcWidth(void *h)');
  const pdcHeight = lib.func('int __cdecl pdcHeight(void *h)');
  const pdcNext = lib.func('int __cdecl pdcNext(void *h, int timeoutMs, uint8_t *dst, int dstSize, uint8_t *err, int errLen)');
  const pdcClose = lib.func('void __cdecl pdcClose(void *h)');

  const err = Buffer.alloc(256);
  const handle = pdcOpen(rect.x, rect.y, rect.width, rect.height, rect.width * rect.height > HALVE_ABOVE ? 1 : 0, err, err.length);
  if (!handle) throw new Error(cstr(err) || 'Desktop Duplication unavailable');
  const width = pdcWidth(handle);
  const height = pdcHeight(handle);
  const size = width * height * 4;
  const picture = Buffer.alloc(size);
  let busy = false;
  let closing = false;
  let closed = false;
  const closeNow = () => {
    if (closed) return;
    closed = true;
    pdcClose(handle);
  };

  return {
    width,
    height,
    stride: width * 4,
    // Desktop Duplication leaves the alpha bytes undefined.
    format: 'BGRX',
    grabAsync(timeoutMs) {
      if (closing) return Promise.resolve(null);
      busy = true;
      return new Promise((resolve, reject) => {
        pdcNext.async(handle, timeoutMs, picture, size, err, err.length, (callError, result) => {
          busy = false;
          // Closed while waiting: the handle could only go once the call returned.
          if (closing) {
            closeNow();
            resolve(null);
          } else if (callError) {
            reject(callError);
          } else if (result === 1) {
            resolve(picture);
          } else if (result === 0) {
            resolve(null);
          } else {
            reject(new Error(cstr(err) || 'capture failed'));
          }
        });
      });
    },
    close() {
      if (closing) return;
      closing = true;
      if (!busy) closeNow();
    },
  };
}
