// Builds the "PairDesk Camera" virtual webcam (native/softcam) on Windows:
// needs CMake and the Visual Studio C++ build tools.
//   npm run build:camera  →  native/bin/win32-x64/PairDeskCamera.dll
// (packaged into resources/camera by electron-builder).

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.platform !== 'win32') {
  console.log('PairDesk Camera is a Windows webcam: nothing to build here.');
  process.exit(0);
}
const build = path.join(root, 'out', 'softcam');
const run = (args) => execFileSync('cmake', args, { cwd: root, stdio: 'inherit' });
run(['-S', path.join(root, 'native', 'softcam'), '-B', build, '-A', 'x64']);
run(['--build', build, '--config', 'Release', '--parallel']);
const dll = path.join(build, 'Release', 'PairDeskCamera.dll');
const dest = path.join(root, 'native', 'bin', 'win32-x64');
fs.mkdirSync(dest, { recursive: true });
fs.copyFileSync(dll, path.join(dest, 'PairDeskCamera.dll'));
console.log(`PairDesk Camera → ${path.join(dest, 'PairDeskCamera.dll')} (${fs.statSync(dll).size} bytes)`);
