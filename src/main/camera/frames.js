// Pixel conversions for the virtual webcam (camera helper process).

/** RGBA → BGR24 (same size, top-down), into `out`. */
export function rgbaToBgr(rgba, out) {
  const n = Math.min(rgba.length >> 2, Math.floor(out.length / 3));
  for (let i = 0, j = 0, k = 0; i < n; i++, j += 4, k += 3) {
    out[k] = rgba[j + 2];
    out[k + 1] = rgba[j + 1];
    out[k + 2] = rgba[j];
  }
}

/** Fills a BGR24 buffer with one colour given as 0xRRGGBB. */
export function fillBgr(out, rgb) {
  const r = (rgb >> 16) & 0xff;
  const g = (rgb >> 8) & 0xff;
  const b = rgb & 0xff;
  for (let k = 0; k + 2 < out.length; k += 3) {
    out[k] = b;
    out[k + 1] = g;
    out[k + 2] = r;
  }
}
