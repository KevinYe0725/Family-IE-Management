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
  const storedValues = new Map([['family-ledger-authenticated', '1']]);
  const storage = {
    getItem(key) { return storedValues.get(key) ?? null; },
    removeItem(key) { storedValues.delete(key); }
  };
  const renderedMessages = [];
  const pending = gate.run(() => delayed.promise, value => committed.push(value));

  assert.equal(expireSessionOnUnauthorized(
    { status: 401 },
    state,
    () => gate.invalidate(),
    { storage, renderLogin: message => renderedMessages.push(message) }
  ), true);
  assert.equal(state.data.session, null);
  assert.equal(state.flash, null);
  assert.equal(storage.getItem('family-ledger-authenticated'), null);
  assert.deepEqual(renderedMessages, [SESSION_EXPIRED_MESSAGE]);
  assert.equal(renderedMessages[0], '登录会话已过期，请重新登录。');

  delayed.resolve('late authenticated snapshot');
  assert.deepEqual(await pending, { current: false });
  assert.deepEqual(committed, []);
});
