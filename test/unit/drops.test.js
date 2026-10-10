// Files dropped at a point of the remote screen (drops.js) and the drag
// replay of the input helper.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DropManager, parseDrop, dragPlan } from '../../src/main/drops.js';
import { createInputCore } from '../../src/main/helper/input-core.js';

const log = { info() {}, warn() {}, error() {} };

test('drop offers are validated', () => {
  assert.deepEqual(parseDrop({ id: 'abcd1234', x: 0.5, y: 1, n: 2 }), { id: 'abcd1234', x: 0.5, y: 1, n: 2 });
  for (const bad of [null, 'x', { id: '../..', x: 0.5, y: 0.5, n: 1 }, { id: 'abcd', x: 2, y: 0, n: 1 },
    { id: 'abcd', x: 0, y: NaN, n: 1 }, { id: 'abcd', x: 0, y: 0, n: 0 }, { id: 'abcd', x: 0, y: 0, n: 101 }]) {
    assert.equal(parseDrop(bad), null, JSON.stringify(bad));
  }
});

test('drag plan: starts beside the drop point, inside the display', () => {
  const rect = { x: 1920, y: 0, width: 1001, height: 501 };
  assert.deepEqual(dragPlan(rect, 0.5, 0.5), { from: { x: 2372, y: 250 }, to: { x: 2420, y: 250 } });
  // Near the left edge the drag starts on the right.
  assert.deepEqual(dragPlan(rect, 0, 0, 2), { from: { x: 2016, y: 0 }, to: { x: 1920, y: 0 } });
});

test('drop manager: stages the files, drops them once all arrived, falls back to Downloads', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pd-drop-'));
  const downloads = path.join(root, 'downloads');
  let target = { sid: 's1', rect: { x: 0, y: 0, width: 1000, height: 800 }, scale: 1, toDip: (p) => p };
  const created = [];
  const windows = {
    createDropSource(point) {
      const handlers = {};
      const win = {
        point,
        destroyed: false,
        webContents: { once: (ev, cb) => { handlers[ev] = cb; setTimeout(cb, 5); }, startDrag(item) { win.dragged = item; } },
        showInactive() {},
        isDestroyed: () => win.destroyed,
        destroy() { win.destroyed = true; },
      };
      created.push(win);
      return win;
    },
  };
  let dragResult = { ok: true };
  const drags = [];
  const manager = new DropManager({
    target: () => target,
    windows,
    input: {
      drag: async (plan) => {
        drags.push(plan);
        // The source window's "dragstart" arrives while the helper drags.
        manager.onDragStart(created.at(-1).webContents, 'icon');
        return dragResult;
      },
      dragStarted() {},
    },
    fallbackDir: () => downloads,
    log,
    stagingRoot: path.join(root, 'staging'),
  });

  // Not the incoming session, or no control: plain Downloads.
  assert.equal(manager.accept('other', { id: 'aaaa', x: 0.5, y: 0.5, n: 1 }), null);
  const a = manager.accept('s1', { id: 'aaaa', x: 0.25, y: 0.5, n: 2 });
  const b = manager.accept('s1', { id: 'aaaa', x: 0.25, y: 0.5, n: 2 });
  assert.equal(manager.accept('s1', { id: 'aaaa', x: 0.25, y: 0.5, n: 2 }), null, 'more files than announced');
  assert.equal(a.directory, b.directory);
  manager.track('t1', a.id);
  manager.track('t2', b.id);
  const f1 = path.join(a.directory, 'un.txt');
  const f2 = path.join(a.directory, 'deux.txt');
  fs.writeFileSync(f1, '1');
  fs.writeFileSync(f2, '2');
  manager.fileDone('t1', f1);
  assert.equal(drags.length, 0, 'waits for every file');
  manager.fileDone('t2', f2);
  await manager.queue;
  assert.equal(drags.length, 1);
  assert.deepEqual(drags[0].to, { x: 250, y: 400 });
  assert.deepEqual(created[0].dragged.files, [f1, f2]);
  assert.ok(fs.existsSync(f1), 'files stay where the target can read them');

  // A drag that cannot start: the files go to Downloads.
  dragResult = { ok: false };
  const c = manager.accept('s1', { id: 'bbbb', x: 0.5, y: 0.5, n: 1 });
  manager.track('t3', c.id);
  const f3 = path.join(c.directory, 'trois.txt');
  fs.writeFileSync(f3, '3');
  manager.fileDone('t3', f3);
  await manager.queue;
  assert.ok(fs.existsSync(path.join(downloads, 'trois.txt')));
  assert.ok(!fs.existsSync(f3));

  // A failed file is not waited for.
  const d = manager.accept('s1', { id: 'cccc', x: 0.5, y: 0.5, n: 2 });
  manager.accept('s1', { id: 'cccc', x: 0.5, y: 0.5, n: 2 });
  manager.track('t4', d.id);
  manager.track('t5', d.id);
  const f4 = path.join(d.directory, 'quatre.txt');
  fs.writeFileSync(f4, '4');
  manager.fileDone('t4', null);
  manager.fileDone('t5', f4);
  await manager.queue;
  assert.ok(fs.existsSync(path.join(downloads, 'quatre.txt')), 'dropped (here: fallback) without the failed file');

  // Session gone before the files arrived: Downloads.
  dragResult = { ok: true };
  const e = manager.accept('s1', { id: 'dddd', x: 0.5, y: 0.5, n: 1 });
  manager.track('t6', e.id);
  const f6 = path.join(e.directory, 'six.txt');
  fs.writeFileSync(f6, '6');
  target = null;
  manager.fileDone('t6', f6);
  await manager.queue;
  assert.ok(fs.existsSync(path.join(downloads, 'six.txt')));
  fs.rmSync(root, { recursive: true, force: true });
});

test('input helper: drag replay presses, waits for the native drag, carries it and releases', async () => {
  const calls = [];
  const injector = {
    moveTo: (x, y) => calls.push(['move', x, y]),
    button: (b, down) => calls.push(['button', b, down]),
    releaseAll: () => calls.push(['release']),
    wheel() {}, key() {}, cursorPos: () => ({ x: 0, y: 0 }),
  };
  const replies = [];
  const core = createInputCore({ injector, toMain: (m) => replies.push(m), log() {} });
  core.handleMain({ type: 'session', id: 's', control: true, rect: { x: 0, y: 0, width: 100, height: 100 } });
  calls.length = 0;
  core.handleMain({ type: 'drag', rid: 1, from: { x: 10, y: 50 }, to: { x: 90, y: 60 } });
  await new Promise((r) => setTimeout(r, 300));
  // Remote input is ignored while the replay owns the mouse.
  core.handleRenderer([['m', 0, 0]]);
  core.handleMain({ type: 'drag-started' });
  await new Promise((r) => setTimeout(r, 1000));
  assert.deepEqual(replies.at(-1), { type: 'reply', rid: 1, value: { ok: true } });
  assert.deepEqual(calls.slice(0, 3), [['release'], ['move', 10, 50], ['button', 0, true]]);
  assert.ok(!calls.some((c) => c[0] === 'move' && c[1] === 0 && c[2] === 0), 'remote move ignored during the drag');
  const ups = calls.filter((c) => c[0] === 'button' && !c[2]);
  assert.equal(ups.length, 1);
  const lastMove = calls.filter((c) => c[0] === 'move').at(-1);
  assert.deepEqual(lastMove, ['move', 90, 60]);

  // Without "drag-started" the button is released where it was pressed.
  calls.length = 0;
  core.handleMain({ type: 'drag', rid: 2, from: { x: 10, y: 50 }, to: { x: 90, y: 60 } });
  await new Promise((r) => setTimeout(r, 2600));
  assert.deepEqual(replies.at(-1), { type: 'reply', rid: 2, value: { ok: false } });
  assert.ok(!calls.some((c) => c[0] === 'move' && c[1] === 90));
  core.dispose();
});
