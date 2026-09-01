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

test('commits only the newest refresh and discards obsolete failures', async () => {
  const gate = new RefreshGate();
  const slow = deferred();
  const current = deferred();
  const committed = [];

  const slowRun = gate.run(() => slow.promise, value => committed.push(value));
  const currentRun = gate.run(() => current.promise, value => committed.push(value));

  current.resolve('newest snapshot');
  assert.deepEqual(await currentRun, { current: true });
  slow.reject(new Error('old request failed'));

  assert.deepEqual(await slowRun, { current: false });
  assert.deepEqual(committed, ['newest snapshot']);
});
