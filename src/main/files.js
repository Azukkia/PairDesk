// Writes files received from a partner into Downloads/PairDesk.

import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';

const RESERVED = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i;

export function sanitizeFileName(name) {
  let clean = String(name ?? '')
    .split(/[\\/]/).pop()
    .replace(/[<>:"|?*\u0000-\u001f]/g, '_')
    .replace(/[. ]+$/, '')
    .trim();
  if (!clean || clean === '.' || clean === '..') clean = 'fichier';
  if (RESERVED.test(clean)) clean = `_${clean}`;
  if (clean.length > 180) {
    const ext = path.extname(clean).slice(0, 20);
    clean = clean.slice(0, 180 - ext.length) + ext;
  }
  return clean;
}

function uniquePath(dir, name) {
  const ext = path.extname(name);
  const base = name.slice(0, name.length - ext.length);
  let candidate = path.join(dir, name);
  for (let i = 1; fs.existsSync(candidate) || fs.existsSync(`${candidate}.pdpart`); i++) {
    candidate = path.join(dir, `${base} (${i})${ext}`);
  }
  return candidate;
}

export class FileReceiver {
  constructor({ getDirectory, log }) {
    this.getDirectory = getDirectory;
    this.log = log;
    this.transfers = new Map();
  }

  begin(owner, name, size) {
    if (!Number.isSafeInteger(size) || size < 0) throw new Error('invalid size');
    const dir = this.getDirectory();
    fs.mkdirSync(dir, { recursive: true });
    const finalPath = uniquePath(dir, sanitizeFileName(name));
    const partPath = `${finalPath}.pdpart`;
    const token = randomBytes(12).toString('hex');
    const stream = fs.createWriteStream(partPath, { flags: 'wx' });
    stream.on('error', (err) => this.log?.warn(`[files] ${err.message}`));
    this.transfers.set(token, { owner, finalPath, partPath, size, written: 0, stream });
    return { token, name: path.basename(finalPath) };
  }

  write(owner, token, chunk) {
    const t = this.transfers.get(token);
    if (!t || t.owner !== owner) return false;
    const buf = Buffer.from(chunk);
    if (t.written + buf.length > t.size) {
      this.abort(owner, token);
      return false;
    }
    t.written += buf.length;
    t.stream.write(buf);
    return true;
  }

  async end(owner, token) {
    const t = this.transfers.get(token);
    if (!t || t.owner !== owner) throw new Error('unknown transfer');
    this.transfers.delete(token);
    await new Promise((resolve, reject) => t.stream.end((err) => (err ? reject(err) : resolve())));
    if (t.written !== t.size) {
      fs.rmSync(t.partPath, { force: true });
      throw new Error('incomplete transfer');
    }
    const finalPath = fs.existsSync(t.finalPath) ? uniquePath(path.dirname(t.finalPath), path.basename(t.finalPath)) : t.finalPath;
    fs.renameSync(t.partPath, finalPath);
    return finalPath;
  }

  abort(owner, token) {
    const t = this.transfers.get(token);
    if (!t || (owner && t.owner !== owner)) return;
    this.transfers.delete(token);
    t.stream.destroy();
    fs.rm(t.partPath, { force: true }, () => {});
  }

  abortAll(owner) {
    for (const [token, t] of this.transfers) {
      if (t.owner === owner) this.abort(owner, token);
    }
  }
}
