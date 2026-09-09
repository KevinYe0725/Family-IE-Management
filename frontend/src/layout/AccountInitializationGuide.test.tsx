import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { Account, Page, Session } from '../api/contracts';
import { ApiError, type ApiRequest } from '../api/client';
import { AuthContext, AuthProvider, useAuth, type AuthContextValue } from '../auth/AuthProvider';
import { LoginPage } from '../auth/LoginPage';
import { ConfirmDialog } from '../features/common';
import { WorkspaceLayout } from './WorkspaceLayout';

const owner: Session = { userId: 7, householdId: 11, role: 'OWNER', username: 'owner', email: 'owner@example.invalid', displayName: '测试用户' };
const account = (id: number, initialized = false): Account => ({ id, name: `现金账户${id}`, type: 'CASH', currency: 'CNY', openingBalance: '0.00', openingConfirmed: initialized, openingOn: initialized ? '2026-09-08' : null, balance: initialized ? '0.00' : null, availableBalance: initialized ? '0.00' : null, archivedAt: null });
function page(items: Account[], index = 0, total = items.length): Page<Account> {
  return { items, page: index, size: 50, totalElements: total, totalPages: Math.ceil(total / 50), hasNext: (index + 1) * 50 < total };
}
function api(load: (index: number) => Promise<Page<Account>>) {
  const writes: string[] = [];
  const request: ApiRequest = async <T,>(path: string, options?: Parameters<ApiRequest>[1]): Promise<T> => {
    if(path==='/api/bank-accounts')return [] as T;
    if (options?.method && options.method !== 'GET') writes.push(path);
    if (path.startsWith('/api/accounts?')) return await load(Number(new URL(path, 'http://test.local').searchParams.get('page'))) as T;
    return new Promise<T>(() => undefined);
  };
  return { request, writes };
}
function Location() { const location = useLocation(); return <output aria-label="当前路径">{location.pathname}{location.search}</output>; }
function harness(request: ApiRequest, initialPath = '/workspace/overview', session = owner, otherDialog = false) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const ui = (current: Session, currentRequest: ApiRequest) => {
    const auth: AuthContextValue = { session: current, status: 'authenticated', login: vi.fn(), logout: vi.fn(), register: vi.fn(), changePassword: vi.fn(), request: currentRequest };
    return <QueryClientProvider client={cache}><AuthContext.Provider value={auth}><MemoryRouter initialEntries={[initialPath]}>
      <WorkspaceLayout session={current} onLogout={vi.fn()} /><Location />
      {otherDialog && <ConfirmDialog open title="已有付款待核对" detail="保留原付款" onClose={() => undefined} onConfirm={() => undefined} />}
    </MemoryRouter></AuthContext.Provider></QueryClientProvider>;
  };
  return { ...render(ui(session, request)), cache, ui };
}
async function settled(cache: QueryClient) { await waitFor(() => expect(cache.isFetching({ queryKey: ['accounts'] })).toBe(0)); }

it.each(['OWNER', 'ADMIN'] as const)('guides %s to the actual account section without writing any balance', async role => {
  const backend = api(async () => page([account(1), account(2, true)]));
  harness(backend.request, '/workspace/transactions', { ...owner, role });
  expect(await screen.findByRole('dialog', { name: '先确认账户期初余额' })).toHaveTextContent('现金账户1');
  expect(screen.getByRole('dialog')).toHaveTextContent('零余额也需要确认');
  await userEvent.click(screen.getByRole('button', { name: '去初始化账户' }));
  expect(screen.getByLabelText('当前路径')).toHaveTextContent('/workspace/transactions?section=accounts');
  expect(await screen.findByRole('heading', { name: '家庭账户' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '确认期初余额' })).toBeInTheDocument();
  expect(screen.queryByRole('dialog', { name: '先确认账户期初余额' })).not.toBeInTheDocument();
  expect(backend.writes).toEqual([]);
});

it('checks later pages instead of treating the first initialized page as complete', async () => {
  const first = Array.from({ length: 50 }, (_, index) => account(index + 1, true));
  const backend = api(async index => index === 0 ? page(first, 0, 51) : page([account(51)], 1, 51));
  harness(backend.request);
  expect(await screen.findByRole('dialog', { name: '先确认账户期初余额' })).toHaveTextContent('现金账户51');
});

it.each(['zero-balance initialized', 'no accounts', 'failed read'] as const)('does not falsely prompt for %s', async scenario => {
  const backend = api(async () => {
    if (scenario === 'failed read') throw new ApiError('网络错误', { status: 503 });
    return page(scenario === 'no accounts' ? [] : [account(1, true)]);
  });
  const { cache } = harness(backend.request);
  await settled(cache);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('does not repeat after dismissal during navigation or an account refresh', async () => {
  const backend = api(async () => page([account(1)]));
  const { cache } = harness(backend.request);
  await screen.findByRole('dialog', { name: '先确认账户期初余额' });
  await userEvent.keyboard('{Escape}');
  await userEvent.click(screen.getByRole('link', { name: '预算管理' }));
  await userEvent.click(screen.getByRole('link', { name: '家庭总览' }));
  await act(async () => { await cache.invalidateQueries({ queryKey: ['accounts'] }); });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('gives members an actionable admin handoff instead of an unauthorized setup button', async () => {
  const backend = api(async () => page([account(1)]));
  harness(backend.request, '/workspace/overview', { ...owner, role: 'MEMBER' });
  expect(await screen.findByRole('dialog', { name: '先确认账户期初余额' })).toHaveTextContent('家庭所有者或管理员');
  expect(screen.queryByRole('button', { name: '去初始化账户' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '查看家庭成员' }));
  expect(screen.getByLabelText('当前路径')).toHaveTextContent('/workspace/family');
  expect(backend.writes).toEqual([]);
});

it('does not let a previous household response show a stale guide after switching accounts', async () => {
  let resolveOld!: (value: Page<Account>) => void;
  const old = api(() => new Promise(resolve => { resolveOld = resolve; }));
  const current = api(async () => page([account(2, true)]));
  const view = harness(old.request);
  await waitFor(() => expect(resolveOld).toBeDefined());
  view.rerender(view.ui({ ...owner, userId: 8, householdId: 12 }, current.request));
  await act(async () => { resolveOld(page([account(1)])); });
  await settled(view.cache);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  const next = api(async () => page([account(3)]));
  view.rerender(view.ui({ ...owner, userId: 9, householdId: 13 }, next.request));
  expect(await screen.findByRole('dialog', { name: '先确认账户期初余额' })).toHaveTextContent('现金账户3');
  expect(screen.getByRole('dialog')).not.toHaveTextContent('现金账户1');
});

it('does not interrupt an existing dialog with a late initialization check', async () => {
  const backend = api(async () => page([account(1)]));
  const { cache } = harness(backend.request, '/workspace/overview', owner, true);
  await settled(cache);
  expect(screen.getAllByRole('dialog')).toHaveLength(1);
  expect(screen.getByRole('dialog', { name: '已有付款待核对' })).toBeInTheDocument();
});

it('leaves users already on the account setup page free to initialize', async () => {
  const errors=vi.spyOn(console,'error').mockImplementation(()=>{});
  const backend = api(async () => page([account(1)]));
  const { cache } = harness(backend.request, '/workspace/transactions?section=accounts');
  await settled(cache);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '家庭账户' })).toBeInTheDocument();
  const duplicateKeys=errors.mock.calls.filter(args=>String(args[0]).includes('same key'));
  errors.mockRestore();
  expect(duplicateKeys).toHaveLength(0);
});

it('checks again after a real same-user logout/login and stops prompting once the server confirms initialization', async () => {
  let initialized = false;
  const json = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 });
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return new Response(JSON.stringify({ error: { code: 'AUTH_REQUIRED', message: '请先登录' } }), { status: 401 });
    if (path === '/api/csrf') return json({ headerName: 'X-CSRF', token: 'test', parameterName: '_csrf' });
    if (path === '/api/auth/login') return json(owner);
    if (path === '/api/auth/logout') return new Response(null, { status: 204 });
    if (path.startsWith('/api/accounts?')) return json(page([account(1, initialized)]));
    return new Promise<Response>(() => undefined);
  });
  function SessionView() {
    const auth = useAuth();
    if (auth.status === 'loading') return <p>正在恢复会话</p>;
    return auth.session ? <WorkspaceLayout session={auth.session} onLogout={auth.logout} /> : <LoginPage />;
  }
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const user = userEvent.setup();
  try {
    render(<QueryClientProvider client={cache}><MemoryRouter initialEntries={['/workspace/overview']}><AuthProvider><SessionView /></AuthProvider></MemoryRouter></QueryClientProvider>);
    const login = async () => {
      await user.click(await screen.findByLabelText('邮箱'));
      await user.paste('owner@example.invalid');
      await user.click(screen.getByLabelText('密码'));
      await user.paste('local-test-password');
      await user.click(screen.getByRole('button', { name: '登录' }));
      await screen.findByRole('heading', { name: '家庭总览' });
    };
    const logout = async () => {
      await user.click(screen.getByRole('button', { name: '个人中心' }));
      await user.click(screen.getByRole('menuitem', { name: '退出登录' }));
      await screen.findByRole('heading', { name: '登录家账' });
    };
    await login();
    await screen.findByRole('dialog', { name: '先确认账户期初余额' });
    await user.click(screen.getByRole('button', { name: '稍后处理' }));
    await logout();
    await login();
    await screen.findByRole('dialog', { name: '先确认账户期初余额' });
    await user.click(screen.getByRole('button', { name: '稍后处理' }));
    initialized = true;
    await logout();
    await login();
    await settled(cache);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  } finally { vi.unstubAllGlobals(); }
}, 15_000); // Three full session transitions; this is a correctness check, not a 5s benchmark.
