// Minimal PNG encoder for small images (cursor bitmaps) in processes without
// Electron's nativeImage (the input helper is a plain Node utility process).

import zlib from 'node:zlib';

const CRC_TABLE = new Int32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c;
});

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
  return out;
}

/**
 * Encodes a premultiplied BGRA bitmap (X11 / Windows cursor layout) as a PNG
 * (straight RGBA, as PNG requires).
 */
export function encodePng(bgra, width, height) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const i = y * stride + x * 4;
      const o = y * (stride + 1) + 1 + x * 4;
      const a = bgra[i + 3];
      const un = (v) => (a === 0 ? 0 : Math.min(255, Math.round((v * 255) / a)));
      raw[o] = un(bgra[i + 2]);
      raw[o + 1] = un(bgra[i + 1]);
      raw[o + 2] = un(bgra[i]);
      raw[o + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

export const pngDataUrl = (bgra, width, height) => `data:image/png;base64,${encodePng(bgra, width, height).toString('base64')}`;
