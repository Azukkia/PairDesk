// Renders assets/logo.svg into the PNG/ICO/ICNS files used by the app, the
// tray (menu bar on macOS) and the installers. Run with `npm run icons` after
// changing the logo.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Resvg } from '@resvg/resvg-js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const svg = fs.readFileSync(path.join(root, 'assets', 'logo.svg'));

function render(size, source = svg) {
  const img = new Resvg(source, { fitTo: { mode: 'width', value: size } }).render();
  return { size, png: img.asPng(), rgba: img.pixels };
}

// macOS menu bar icon: a "template" image, black on transparent, that macOS
// tints for light and dark menu bars. The logo's two screens and pointer,
// without the coloured background, the pointer cut out of the front screen.
const templateSvg = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="38 38 180 180">
  <defs>
    <mask id="gap"><rect width="256" height="256" fill="#fff"/><rect x="88" y="92" width="130" height="100" rx="21" fill="#000"/></mask>
    <mask id="pointer"><rect width="256" height="256" fill="#fff"/>
      <path d="M136 117 L136 163 L148 151.5 L157 172 L167 167.5 L158 147.5 L174.5 147.5 Z" fill="#000" stroke="#000" stroke-width="5" stroke-linejoin="round"/></mask>
  </defs>
  <rect x="50" y="58" width="108" height="78" rx="15" fill="none" stroke="#000" stroke-width="16" mask="url(#gap)"/>
  <rect x="98" y="102" width="110" height="80" rx="15" fill="#000" mask="url(#pointer)"/>
  <rect x="134" y="192" width="38" height="13" rx="6.5" fill="#000"/>
</svg>`);

// Apple icon image: PNG entries ic11/ic12 (16/32 pt @2x), ic07/ic08/ic09 (128/256/512 pt),
// ic13/ic14/ic10 (128/256/512 pt @2x).
function icns(images) {
  const types = [['ic11', 32], ['ic12', 64], ['ic07', 128], ['ic13', 256], ['ic08', 256], ['ic14', 512], ['ic09', 512], ['ic10', 1024]];
  const entries = types.map(([type, size]) => {
    const head = Buffer.alloc(8);
    head.write(type, 0, 'ascii');
    head.writeUInt32BE(8 + images[size].png.length, 4);
    return Buffer.concat([head, images[size].png]);
  });
  const head = Buffer.alloc(8);
  head.write('icns', 0, 'ascii');
  head.writeUInt32BE(8 + entries.reduce((n, e) => n + e.length, 0), 4);
  return Buffer.concat([head, ...entries]);
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

const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512, 1024];
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
write('assets/trayTemplate.png', render(16, templateSvg).png);
write('assets/trayTemplate@2x.png', render(32, templateSvg).png);
write('build/icon.png', images[512].png);
write('build/icon.ico', appIco);
write('build/icon.icns', icns(images));
