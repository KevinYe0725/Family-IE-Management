import assert from 'node:assert/strict';
import test from 'node:test';
import { RefreshGate } from '../../main/resources/static/refresh-gate.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test('discards an older success after a newer refresh commits first', async () => {
  const gate = new RefreshGate();
  const older = deferred();
  const newer = deferred();
  const committed = [];

  const olderRun = gate.run(() => older.promise, value => committed.push(value));
  const newerRun = gate.run(() => newer.promise, value => committed.push(value));

  newer.resolve('newest snapshot');
  assert.deepEqual(await newerRun, { current: true });
  older.resolve('older snapshot');

  assert.deepEqual(await olderRun, { current: false });
  assert.deepEqual(committed, ['newest snapshot']);
});

test('discards a delayed success after invalidation', async () => {
  const gate = new RefreshGate();
  const delayed = deferred();
  const committed = [];

  const run = gate.run(() => delayed.promise, value => committed.push(value));
  gate.invalidate();
  delayed.resolve('late snapshot');

  assert.deepEqual(await run, { current: false });
  assert.deepEqual(committed, []);
});

test('discards a delayed failure after invalidation without reporting it', async () => {
  const gate = new RefreshGate();
  const delayed = deferred();

  const run = gate.run(() => delayed.promise, () => assert.fail('obsolete refresh must not commit'));
  gate.invalidate();
  delayed.reject(new Error('late request failed'));

  assert.deepEqual(await run, { current: false });
});
