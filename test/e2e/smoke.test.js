// Smoke test: PairDesk starts, its main window shows an ID and remote control
// can be loaded (koffi's native binary is there). Runs against the sources,
// or a packaged build with PAIRDESK_PACKAGED_EXE, e.g. the Mac app:
//   PAIRDESK_PACKAGED_EXE=dist/mac-universal/PairDesk.app/Contents/MacOS/PairDesk node --test test/e2e/smoke.test.js

import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { artifacts, startSignaling, launch, poll, dumpDiagnostics } from './helpers.js';

test('PairDesk starts and shows its ID', { timeout: 120_000 }, async (t) => {
  const signaling = await startSignaling();
  const pc = await launch('smoke', signaling.env);
  pc.name = 'smoke';
  t.after(async () => {
    await pc.app.close().catch(() => {});
    await signaling.close();
  });
  try {
    const id = await poll(async () => {
      const text = (await pc.main.textContent('#my-id')).replace(/\s/g, '');
      return /^[1-9]\d{8}$/.test(text) ? text : null;
    }, { message: 'ID shown in the main window' });
    const state = await pc.main.evaluate(() => window.pairdesk.invoke('app:state'));
    assert.equal(state.id, id);
    assert.equal(state.platform, process.platform);
    assert.equal(state.input.available, true, `remote control unavailable: ${state.input.reason}`);
    if (process.platform === 'darwin') {
      // What this Mac grants (CI runners: usually nothing); the banner says it.
      process.stderr.write(`macOS permissions: ${JSON.stringify(state.permissions)}\n`);
      assert.equal(typeof state.permissions?.accessibility, 'boolean');
      const missing = state.permissions.screen !== 'granted' || !state.permissions.accessibility;
      assert.equal(Boolean(await pc.main.$('#permissions-banner')), missing);
    } else {
      assert.equal(state.permissions, null);
    }
    await poll(async () => (await pc.main.$('.dot.online')) !== null, { message: 'online' });
    await pc.main.screenshot({ path: path.join(artifacts, 'smoke-main.png') }).catch(() => {});
  } catch (err) {
    await dumpDiagnostics([pc]);
    throw err;
  }
});
