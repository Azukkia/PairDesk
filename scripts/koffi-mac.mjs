// A universal Mac build packages PairDesk twice, for x64 and arm64, from the
// same node_modules, but npm only installs koffi's native binary for the
// machine's own architecture (@koromix/koffi-darwin-arm64 on Apple silicon).
// This adds the missing Mac one(s), at the version and with the integrity
// recorded in package-lock.json, without changing any project file.
//
//   node scripts/koffi-mac.mjs [project root]

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(process.argv[2] || path.join(path.dirname(fileURLToPath(import.meta.url)), '..'));
const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';

for (const arch of ['x64', 'arm64']) {
  const name = `@koromix/koffi-darwin-${arch}`;
  const locked = lock.packages?.[`node_modules/${name}`];
  if (!locked?.version || !locked.integrity) throw new Error(`${name} is not in package-lock.json`);
  const dir = path.join(root, 'node_modules', name);
  try {
    if (JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).version === locked.version) {
      console.log(`${name}@${locked.version} already installed`);
      continue;
    }
  } catch {
    /* missing */
  }
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'koffi-mac-'));
  try {
    const out = execFileSync(npm, ['pack', `${name}@${locked.version}`, '--silent', '--pack-destination', tmp], { encoding: 'utf8' });
    const tarball = path.join(tmp, out.trim().split('\n').pop());
    const [algo, expected] = locked.integrity.split('-');
    const actual = crypto.createHash(algo).update(fs.readFileSync(tarball)).digest('base64');
    if (actual !== expected) throw new Error(`${name}: integrity mismatch with package-lock.json`);
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    execFileSync('tar', ['-xzf', tarball, '-C', dir, '--strip-components=1']);
    console.log(`added ${name}@${locked.version}`);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}
