import assert from 'node:assert/strict';
import test from 'node:test';
import { RefreshGate } from '../../main/resources/static/refresh-gate.js';
import { SESSION_EXPIRED_MESSAGE, expireSessionOnUnauthorized } from '../../main/resources/static/session-expiry.js';

function deferred() {
  let resolve;
  const promise = new Promise(resolvePromise => { resolve = resolvePromise; });
  return { promise, resolve };
}

test('401 expires the session and invalidates a pending refresh before it can commit', async () => {
  const gate = new RefreshGate();
  const delayed = deferred();
  const committed = [];
  const state = { data: { session: { username: 'demo' } }, flash: { message: 'old message' } };
  const pending = gate.run(() => delayed.promise, value => committed.push(value));

  assert.equal(expireSessionOnUnauthorized({ status: 401 }, state, () => gate.invalidate()), true);
  assert.equal(state.data.session, null);
  assert.equal(state.flash, null);
  assert.equal(SESSION_EXPIRED_MESSAGE, '登录会话已过期，请重新登录。');

  delayed.resolve('late authenticated snapshot');
  assert.deepEqual(await pending, { current: false });
  assert.deepEqual(committed, []);
});
