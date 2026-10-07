// Renders assets/logo.svg into the PNG/ICO files used by the app, the tray
// and the installer. Run with `npm run icons` after changing the logo.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Resvg } from '@resvg/resvg-js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const svg = fs.readFileSync(path.join(root, 'assets', 'logo.svg'));

function render(size) {
  const img = new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render();
  return { size, png: img.asPng(), rgba: img.pixels };
}

// Classic ICO: 32-bit BMP entries for small sizes, PNG for 256 px.
function ico(images) {
  const entries = images.map(({ size, png, rgba }) => {
    if (size >= 256) return { size, data: png };
    const header = Buffer.alloc(40);
    header.writeUInt32LE(40, 0);
    header.writeInt32LE(size, 4);
    header.writeInt32LE(size * 2, 8);
    header.writeUInt16LE(1, 12);
    header.writeUInt16LE(32, 14);
    header.writeUInt32LE(size * size * 4, 20);
    const pixels = Buffer.alloc(size * size * 4);
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const src = (y * size + x) * 4;
        const dst = ((size - 1 - y) * size + x) * 4;
        pixels[dst] = rgba[src + 2];
        pixels[dst + 1] = rgba[src + 1];
        pixels[dst + 2] = rgba[src];
        pixels[dst + 3] = rgba[src + 3];
      }
    }
    const maskRow = Math.ceil(size / 32) * 4;
    return { size, data: Buffer.concat([header, pixels, Buffer.alloc(maskRow * size)]) };
  });
  const dir = Buffer.alloc(6 + 16 * entries.length);
  dir.writeUInt16LE(0, 0);
  dir.writeUInt16LE(1, 2);
  dir.writeUInt16LE(entries.length, 4);
  let offset = dir.length;
  entries.forEach((e, i) => {
    const o = 6 + i * 16;
    dir.writeUInt8(e.size >= 256 ? 0 : e.size, o);
    dir.writeUInt8(e.size >= 256 ? 0 : e.size, o + 1);
    dir.writeUInt8(0, o + 2);
    dir.writeUInt8(0, o + 3);
    dir.writeUInt16LE(1, o + 4);
    dir.writeUInt16LE(32, o + 6);
    dir.writeUInt32LE(e.data.length, o + 8);
    dir.writeUInt32LE(offset, o + 12);
    offset += e.data.length;
  });
  return Buffer.concat([dir, ...entries.map((e) => e.data)]);
}

const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512];
const images = Object.fromEntries(sizes.map((s) => [s, render(s)]));
const write = (rel, data) => {
  fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
  fs.writeFileSync(path.join(root, rel), data);
  console.log('wrote', rel, data.length, 'bytes');
};

const appIco = ico([16, 20, 24, 32, 40, 48, 64, 128, 256].map((s) => images[s]));
write('assets/icon.png', images[512].png);
write('assets/icon.ico', appIco);
write('assets/tray.png', images[32].png);
write('assets/tray.ico', ico([16, 20, 24, 32, 48].map((s) => images[s])));
write('build/icon.png', images[512].png);
write('build/icon.ico', appIco);
