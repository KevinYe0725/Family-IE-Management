import { act, render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider, QueryObserver } from '@tanstack/react-query';
import { AuthProvider, useAuth, type AuthContextValue } from './AuthProvider';

const session = { userId: 7, householdId: 1, email: 'a@test.local', displayName: 'A', role: 'OWNER', username: 'a' };
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status === 200 ? { data } : { error: { code: 'FORBIDDEN', message: 'denied' } }), { status });
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
let auth: AuthContextValue;
function Probe() { auth = useAuth(); return <span>{auth.session?.displayName ?? auth.status}</span>; }
async function setup(handler: (path: string, options?: RequestInit) => Promise<Response>) {
  vi.stubGlobal('fetch', (input: RequestInfo | URL, options?: RequestInit) => {
    const path = String(input);
    if (path === '/api/csrf') return Promise.resolve(response({ headerName: 'X-CSRF', token: 'test', parameterName: '_csrf' }));
    return handler(path, options);
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  render(<QueryClientProvider client={cache}><AuthProvider><Probe /></AuthProvider></QueryClientProvider>);
  await waitFor(() => expect(auth.status).toBe('authenticated'));
  return cache;
}
afterEach(() => vi.unstubAllGlobals());

it.each(['release', 'logout', 'another login'])('retains the same session cache during unresolved recovery, then %s restores the session boundary', async end => {
 const old = deferred<Response>();
 const cache = await setup(async path => path === '/api/session' ? response(session) : path === '/api/slow-parent' ? old.promise : path === '/api/auth/login' ? response({ ...session, userId: 8, householdId: 2, displayName: 'B' }) : path === '/api/auth/logout' ? response(null) : response(null, 401));
 cache.setQueryData(['loans', 'detail', 4], { id: 4, name: 'A private loan' });
 const pending = auth.request('/api/slow-parent').catch(error => error);
 const release = auth.request.beginRecoverableOperation!();
 await act(async () => { old.resolve(response(null, 401)); await pending; });
 expect(await pending).toMatchObject({ status: 401, sessionExpired: false });
 expect(auth.session?.userId).toBe(7); expect(cache.getQueryData(['loans', 'detail', 4])).toEqual({ id: 4, name: 'A private loan' });
 if (end === 'release') release();
 else if (end === 'logout') await act(async () => { await auth.logout(); });
 else await act(async () => { await auth.login('b@test.local', 'password'); });
 if (end !== 'release') expect(cache.getQueryData(['loans', 'detail', 4])).toBeUndefined();
 if (end === 'another login') expect(auth.session?.householdId).toBe(2);
 await act(async () => { await auth.request('/api/expired').catch(() => {}); });
 expect(auth.status).toBe('anonymous'); expect(cache.getQueryCache().getAll()).toHaveLength(0);
 release();
});

it.each(['/api/transactions/1', '/api/budgets/1', '/api/accounts/1', '/api/assets/1/valuations', '/api/investment-trades/1', '/api/loans/1/prepay', '/api/loan-installments/1/confirm', '/api/recurring-occurrences/1/confirm'])('refreshes inactive previously viewed totals before %s write returns', async path => {
  let saved = false;
  const cache = await setup(async url => url === '/api/session' ? response(session) : (saved = true, response({ id: 1 })));
  const query = { queryKey: ['net-worth'], queryFn: async () => ({ total: saved ? '90.00' : '100.00' }) };
  await cache.fetchQuery(query);
  await act(async () => { await auth.request(path, { method: 'PATCH', body: {} }); });
  expect(cache.getQueryData(['net-worth'])).toEqual({ total: '90.00' });
  // Immediate return to a summary with infinite staleTime must use new totals.
  expect(await cache.fetchQuery(query)).toEqual({ total: '90.00' });
});

it.each([
  ['/api/investment-plans/occurrences/11/confirm', 'POST', ['net-worth', 'summary'], '100.00', '90.00'],
  ['/api/investment-trades/44', 'PATCH', ['investment-plans', 0, 1], 'old execution', 'revised execution'],
  ['/api/investment-trades/44', 'DELETE', ['investment-plans', 0, 1], 'confirmed', 'trade reversed']
] as const)('refreshes inactive investment reports or plan history after %s', async (path, method, queryKey, before, after) => {
  let saved = false;
  const cache = await setup(async url => url === '/api/session' ? response(session) : (saved = true, response({ id: 1 })));
  const query = { queryKey, queryFn: async () => saved ? after : before };
  await cache.fetchQuery(query);
  await act(async () => { await auth.request(path, { method, body: {} }); });
  expect(cache.getQueryData(queryKey)).toBe(after);
  expect(await cache.fetchQuery(query)).toBe(after);
});

it('cancels an older read, refreshes active and inactive keys, and ignores its late response', async () => {
  const old = deferred<unknown>(); let reads = 0;
  const cache = await setup(async path => response(path === '/api/session' ? session : { id: 1 }));
  const observer = new QueryObserver(cache, { queryKey: ['net-worth'], queryFn: () => ++reads === 1 ? old.promise : Promise.resolve({ total: '90.00' }) });
  const stop = observer.subscribe(() => undefined);
  await cache.fetchQuery({ queryKey: ['transactions', 'recent', '2026-09'], queryFn: async () => ['new'] });
  cache.setQueryData(['transactions', 'recent', '2026-09'], ['old']);
  await act(async () => { await auth.request('/api/transactions/1', { method: 'DELETE' }); });
  expect(cache.getQueryData(['net-worth'])).toEqual({ total: '90.00' });
  expect(cache.getQueryData(['transactions', 'recent', '2026-09'])).toEqual(['new']);
  old.resolve({ total: '100.00' }); await Promise.resolve();
  expect(cache.getQueryData(['net-worth'])).toEqual({ total: '90.00' }); stop();
});

it('preserves failed writes without refreshing or reporting success', async () => {
  const cache = await setup(async path => path === '/api/session' ? response(session) : response(null, 403));
  await cache.fetchQuery({ queryKey: ['net-worth'], queryFn: async () => '100.00' });
  await expect(auth.request('/api/transactions/1', { method: 'DELETE' })).rejects.toMatchObject({ status: 403 });
  expect(cache.getQueryState(['net-worth'])).toMatchObject({ data: '100.00', isInvalidated: false });
});

it('keeps a persisted write successful while failed refresh stays stale and errored', async () => {
  const cache = await setup(async path => response(path === '/api/session' ? session : { id: 1 }));
  let fail = false;
  await cache.fetchQuery({ queryKey: ['net-worth'], queryFn: async () => { if (fail) throw new Error('read unavailable'); return '100.00'; } });
  fail = true;
  await expect(auth.request('/api/transactions', { method: 'POST' })).resolves.toEqual({ id: 1 });
  expect(cache.getQueryState(['net-worth'])).toMatchObject({ data: '100.00', status: 'error', isInvalidated: true });
});

it.each([200, 401])('ignores an old-user business response (%i) after another login', async status => {
  const old = deferred<Response>();
  const cache = await setup(async path => path === '/api/session' ? response(session) : path === '/api/auth/login' ? response({ ...session, userId: 8, displayName: 'B' }) : old.promise);
  const pending = auth.request('/api/transactions/1', { method: 'PATCH' }).catch(error => error);
  await act(async () => { await auth.login('b@test.local', 'password'); });
  cache.setQueryData(['net-worth'], 'B total');
  await act(async () => { old.resolve(response({ id: 1 }, status)); await pending; });
  expect(auth.session?.userId).toBe(8);
  expect(cache.getQueryData(['net-worth'])).toBe('B total');
  expect(await pending).toMatchObject({ name: 'AbortError' });
});

it('prevents late login from restoring a session after logout', async () => {
  const old = deferred<Response>();
  const cache = await setup(async path => path === '/api/session' ? response(session) : path === '/api/auth/login' ? old.promise : response(null));
  const pending = auth.login('b@test.local', 'password').catch(error => error);
  await act(async () => { await auth.logout(); });
  await act(async () => { old.resolve(response({ ...session, userId: 8 })); await pending; });
  expect(auth.status).toBe('anonymous'); expect(cache.getQueryCache().getAll()).toHaveLength(0);
});

it.each([200, 401])('ignores late bootstrap (%i) after login', async status => {
  const old = deferred<Response>();
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return old.promise;
    if (path === '/api/csrf') return response({ headerName: 'X-CSRF', token: 'test' });
    return response({ ...session, userId: 8, displayName: 'B' });
  });
  const cache = new QueryClient();
  render(<QueryClientProvider client={cache}><AuthProvider><Probe /></AuthProvider></QueryClientProvider>);
  await act(async () => { await auth.login('b@test.local', 'password'); });
  await act(async () => { old.resolve(response(session, status)); });
  expect(auth.session?.userId).toBe(8);
});

it('does not allow an older logout to clear a newer login', async () => {
  const old = deferred<Response>(); let logoutStarted = false;
  const cache = await setup(async path => {
    if (path === '/api/session') return response(session);
    if (path === '/api/auth/logout') { logoutStarted = true; return old.promise; }
    return response({ ...session, userId: 8, displayName: 'B' });
  });
  const pending = auth.logout().catch(error => error);
  await waitFor(() => expect(logoutStarted).toBe(true));
  await act(async () => { await auth.login('b@test.local', 'password'); });
  cache.setQueryData(['net-worth'], 'B total');
  await act(async () => { old.resolve(response(null)); await pending; });
  expect(auth.session?.userId).toBe(8); expect(cache.getQueryData(['net-worth'])).toBe('B total');
});

it('does not let a late GET from an old session expire or return data into the new session', async () => {
  const old = deferred<Response>();
  const cache = await setup(async path => path === '/api/session' ? response(session) : path === '/api/auth/login' ? response({ ...session, userId: 8 }) : old.promise);
  const pending = auth.request('/api/net-worth').catch(error => error);
  await act(async () => { await auth.login('b@test.local', 'password'); });
  cache.setQueryData(['net-worth'], 'B total');
  await act(async () => { old.resolve(response(null, 401)); await pending; });
  expect(await pending).toMatchObject({ name: 'AbortError' });
  expect(auth.session?.userId).toBe(8); expect(cache.getQueryData(['net-worth'])).toBe('B total');
});

it.each([
  ['/api/transactions/1', ['transactions', 'accounts', 'budget-usage', 'dashboard', 'net-worth', 'analysis', 'debt-analysis', 'notifications', 'plugin']],
  ['/api/budgets/1', ['budget-usage', 'budget-revisions', 'dashboard', 'notifications']],
  ['/api/assets/1/valuations', ['assets', 'asset-valuations', 'net-worth', 'notifications']],
  ['/api/investment-accounts/1', ['investment-accounts', 'investment-trades', 'portfolio', 'net-worth']],
  ['/api/investment-plans/1', ['investment-plans', 'investment-accounts', 'investment-trades', 'portfolio', 'net-worth', 'notifications']],
  ['/api/market-quotes/refresh', ['market-quotes', 'portfolio', 'net-worth']],
  ['/api/loans/1/prepay', ['loans', 'loan-schedule', 'debt-analysis', 'transactions', 'accounts', 'net-worth']],
  ['/api/recurring-rules/1', ['recurring-rules', 'recurring-occurrences', 'transactions', 'budget-usage', 'notifications']],
  ['/api/notifications/1/read', ['notifications']]
] as const)('refreshes all linked query shapes after %s', async (path, roots) => {
  let saved = false;
  const cache = await setup(async url => url === '/api/session' ? response(session) : (saved = true, response({ id: 1 })));
  for (const root of roots) {
    await cache.fetchQuery({ queryKey: [root, 'page', 2], queryFn: async () => saved ? 'after' : 'before' });
  }
  await act(async () => { await auth.request(path, { method: 'POST' }); });
  for (const root of roots) expect(cache.getQueryData([root, 'page', 2])).toBe('after');
});

it('does not let a second write cancel the first write refresh and report premature success', async () => {
  const firstRead = deferred<string>(); const secondRead = deferred<string>(); let reads = 0; let writes = 0;
  const cache = await setup(async path => path === '/api/session' ? response(session) : response({ id: ++writes }));
  await cache.fetchQuery({ queryKey: ['net-worth'], queryFn: async () => { reads += 1; return reads === 1 ? '100.00' : reads === 2 ? firstRead.promise : secondRead.promise; } });
  let firstDone = false; let secondDone = false;
  const first = auth.request('/api/transactions', { method: 'POST' }).then(() => { firstDone = true; });
  await waitFor(() => expect(reads).toBe(2));
  const second = auth.request('/api/transactions', { method: 'POST' }).then(() => { secondDone = true; });
  await waitFor(() => expect(writes).toBe(2));
  expect(firstDone).toBe(false); expect(secondDone).toBe(false);
  firstRead.resolve('90.00'); await first;
  await waitFor(() => expect(reads).toBe(3));
  expect(secondDone).toBe(false);
  secondRead.resolve('80.00'); await second;
  expect(cache.getQueryData(['net-worth'])).toBe('80.00');
});

it('does not repopulate an old-user cache when login occurs during the awaited refresh', async () => {
  const oldRead = deferred<Response>(); let reads = 0;
  const cache = await setup(async path => {
    if (path === '/api/session') return response(session);
    if (path === '/api/auth/login') return response({ ...session, userId: 8 });
    if (path === '/api/net-worth') return ++reads === 1 ? response('A total') : oldRead.promise;
    return response({ id: 1 });
  });
  await cache.fetchQuery({ queryKey: ['net-worth'], queryFn: () => auth.request('/api/net-worth') });
  const pending = auth.request('/api/transactions', { method: 'POST' }).catch(error => error);
  await waitFor(() => expect(reads).toBe(2));
  await act(async () => { await auth.login('b@test.local', 'password'); });
  cache.setQueryData(['net-worth'], 'B total');
  await act(async () => { oldRead.resolve(response('A updated')); await pending; });
  expect(await pending).toMatchObject({ name: 'AbortError' });
  expect(auth.session?.userId).toBe(8); expect(cache.getQueryData(['net-worth'])).toBe('B total');
});

it.each([
  ['/api/accounts/1', 'accountName', '日常账户', '家庭新账户'],
  ['/api/categories/1', 'categoryName', '日常支出', '家庭新分类']
] as const)('refreshes inactive recurring labels immediately after renaming %s', async (path, field, before, after) => {
  let name: string = before;
  const cache = await setup(async url => {
    if (url === '/api/session') return response(session);
    name = after;
    return response({ id: 1, name });
  });
  const rulesPage = {
    queryKey: ['recurring-rules', 'page', 0],
    queryFn: async () => ({ items: [{ id: 1, [field]: name }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false })
  };
  const pendingLabels = {
    queryKey: ['recurring-rules', 'all-reference'],
    queryFn: async () => [{ id: 1, [field]: name }]
  };
  await cache.fetchQuery(rulesPage);
  await cache.fetchQuery(pendingLabels);
  await act(async () => { await auth.request(path, { method: 'PATCH', body: { name: after } }); });
  expect(cache.getQueryData(rulesPage.queryKey)).toMatchObject({ items: [{ id: 1, [field]: after }] });
  expect(cache.getQueryData(pendingLabels.queryKey)).toEqual([{ id: 1, [field]: after }]);
  // Immediate navigation reuses these infinite-staleTime cached shapes.
  expect(await cache.fetchQuery(rulesPage)).toMatchObject({ items: [{ id: 1, [field]: after }] });
  expect(await cache.fetchQuery(pendingLabels)).toEqual([{ id: 1, [field]: after }]);
});
