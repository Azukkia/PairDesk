// Native clipboard helpers that Electron does not offer.

import koffi from 'koffi';

/**
 * Windows: returns a function reading the clipboard sequence number (it
 * changes whenever the clipboard content changes), so polling can skip the
 * actual read. Elsewhere returns null.
 */
export function clipboardSequence(log) {
  if (process.platform !== 'win32') return null;
  try {
    const user32 = koffi.load('user32.dll');
    const GetClipboardSequenceNumber = user32.func('uint32_t __stdcall GetClipboardSequenceNumber()');
    return () => GetClipboardSequenceNumber();
  } catch (err) {
    log?.warn(`[clipboard] sequence number unavailable: ${err.message}`);
    return null;
  }
}
