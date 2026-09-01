import assert from 'node:assert/strict';
import test from 'node:test';
import { createApiClient } from '../../main/resources/static/api-client.js';
import { expireSessionOnUnauthorized, SESSION_EXPIRED_MESSAGE } from '../../main/resources/static/session-expiry.js';

function unauthorizedResponse() {
  return {
    ok: false,
    status: 401,
    async json() {
      return { error: { code: 'AUTH_REQUIRED', message: '请先登录' } };
    }
  };
}

function scenario() {
  const state = { data: { session: { username: 'demo' } }, flash: { message: '旧消息' } };
  const storedValues = new Map([['family-ledger-authenticated', '1']]);
  const storage = {
    getItem(key) { return storedValues.get(key) ?? null; },
    removeItem(key) { storedValues.delete(key); }
  };
  const rendered = [];
  let invalidations = 0;
  const requests = [];
  const client = createApiClient({
    fetchImpl: async (path, options) => {
      requests.push({ path, options });
      return unauthorizedResponse();
    },
    onUnauthorized: error => expireSessionOnUnauthorized(
      error,
      state,
      () => { invalidations += 1; },
      { storage, renderLogin: message => rendered.push(message) }
    )
  });
  return { client, state, storage, rendered, requests, invalidations: () => invalidations };
}

for (const action of [
  { name: 'common write', path: '/api/transactions', options: { method: 'POST' }, responseType: 'json' },
  { name: 'CSV export', path: '/api/export.csv', options: {}, responseType: 'blob' },
  { name: 'logout', path: '/api/auth/logout', options: { method: 'POST' }, responseType: 'json' }
]) {
  test(`${action.name} 401 performs the complete session-expiry transition`, async () => {
    const harness = scenario();

    await assert.rejects(
      () => harness.client.request(action.path, action.options, action.responseType),
      error => error.status === 401 && error.sessionExpired === true
    );

    assert.equal(harness.state.data.session, null);
    assert.equal(harness.state.flash, null);
    assert.equal(harness.storage.getItem('family-ledger-authenticated'), null);
    assert.deepEqual(harness.rendered, [SESSION_EXPIRED_MESSAGE]);
    assert.equal(harness.invalidations(), 1);
    assert.equal(harness.requests[0].path, action.path);
    assert.equal(harness.requests[0].options.credentials, 'same-origin');
  });
}

test('concurrent 401 responses render the expired login state only once', async () => {
  const harness = scenario();

  await Promise.allSettled([
    harness.client.request('/api/members'),
    harness.client.request('/api/categories')
  ]);

  assert.deepEqual(harness.rendered, [SESSION_EXPIRED_MESSAGE]);
  assert.equal(harness.state.data.session, null);
  assert.equal(harness.storage.getItem('family-ledger-authenticated'), null);
});
