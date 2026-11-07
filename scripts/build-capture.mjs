// Builds the screen capture without cursor (native/capture, DXGI Desktop
// Duplication) on Windows: needs CMake and the Visual Studio C++ build tools.
//   npm run build:capture  →  native/bin/win32-x64/PairDeskCapture.dll
// (packaged into resources/capture by electron-builder). Without it PairDesk
// uses Chromium's capture, which paints the cursor into the picture.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.platform !== 'win32') {
  console.log('The native capture DLL is for Windows: nothing to build here.');
  process.exit(0);
}
const build = path.join(root, 'out', 'capture');
const run = (args) => execFileSync('cmake', args, { cwd: root, stdio: 'inherit' });
run(['-S', path.join(root, 'native', 'capture'), '-B', build, '-A', 'x64']);
run(['--build', build, '--config', 'Release', '--parallel']);
const dll = path.join(build, 'Release', 'PairDeskCapture.dll');
const dest = path.join(root, 'native', 'bin', 'win32-x64');
fs.mkdirSync(dest, { recursive: true });
fs.copyFileSync(dll, path.join(dest, 'PairDeskCapture.dll'));
console.log(`PairDesk capture → ${path.join(dest, 'PairDeskCapture.dll')} (${fs.statSync(dll).size} bytes)`);
