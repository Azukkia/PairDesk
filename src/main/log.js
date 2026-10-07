// Minimal file logger (userData/logs/main.log, rotated at 2 MB).

import fs from 'node:fs';
import path from 'node:path';

let stream = null;
let file = null;

function write(level, args) {
  const text = args.map((a) => (a instanceof Error ? a.stack || a.message : typeof a === 'string' ? a : JSON.stringify(a))).join(' ');
  const line = `${new Date().toISOString()} [${level}] ${text}\n`;
  if (level === 'error' || level === 'warn') process.stderr.write(line);
  else if (process.env.PAIRDESK_DEBUG) process.stdout.write(line);
  if (stream) stream.write(line);
}

export const log = {
  info: (...a) => write('info', a),
  warn: (...a) => write('warn', a),
  error: (...a) => write('error', a),
  debug: (...a) => {
    if (process.env.PAIRDESK_DEBUG) write('debug', a);
  },
  get file() {
    return file;
  },
};

export function initLogFile(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true });
    file = path.join(dir, 'main.log');
    try {
      if (fs.statSync(file).size > 2 * 1024 * 1024) fs.renameSync(file, path.join(dir, 'main.old.log'));
    } catch {
      /* no previous log */
    }
    stream = fs.createWriteStream(file, { flags: 'a' });
  } catch (err) {
    process.stderr.write(`cannot open log file: ${err.message}\n`);
  }
}
