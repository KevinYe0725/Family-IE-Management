import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentsPage } from './InvestmentsPage';
import { OverseasMarketPanel } from './OverseasMarketPanel';
import type { RequestFn } from '../common';

// JSDOM has no canvas; chart drawing is checked separately in a real browser.
vi.mock('klinecharts', () => ({ init: () => null, dispose: () => {} }));
const hk = { market: 'HK', symbol: '00700', name: '騰訊控股', currency: 'HKD', exchange: 'HKEX', timezone: 'Asia/Hong_Kong' };
const us = { market: 'US', symbol: 'AAPL', name: 'Apple Inc.', currency: 'USD', exchange: 'NASDAQ', timezone: 'America/New_York' };
it('does not select a quote result while the user is composing Chinese text', async () => {
  const paths:string[]=[];
  const request:RequestFn=async<T,>(path:string)=>{paths.push(path);return {items:[hk],state:'READY',stale:false,hasNext:false} as T;};
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><OverseasMarketPanel request={request} market="HK"/></QueryClientProvider>);
  await userEvent.click(screen.getByRole('combobox',{name:'证券'}));
  const option=await screen.findByRole('option',{name:/00700.*騰訊/});
  fireEvent.compositionStart(screen.getByRole('textbox'));
  await userEvent.click(option);
  expect(paths.some(path=>path.includes('/candles'))).toBe(false);
});
function setup() {
  const requests: Array<{ path: string; method?: string }> = [];
  let fail = false;
  const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
    requests.push({ path, method: options?.method });
    if (path.startsWith('/api/overseas-market/')) {
      if (fail) throw new Error('暂时不可用');
      const instrument = path.includes('market=US') ? us : hk;
      if (path.includes('/search')) return { items: [instrument], state: 'READY', error: null, updatedAt: '2026-09-08T12:00:00Z', stale: false, hasNext: false } as T;
      return { instrument, symbol: instrument.symbol, source: 'SINA', adjustment: 'none', asOf: '2026-09-04', fetchedAt: '2026-09-08T12:00:00Z', stale: false, supported: true, bars: [{ timestamp: Date.parse(instrument.market === 'US' ? '2026-09-04T00:00:00-04:00' : '2026-09-04T00:00:00+08:00'), open: 319, high: 320, low: 318, close: 319.97, volume: 100, turnover: null }] } as T;
    }
    if (path === '/api/investment-setup') return { completed: false, hasAccounts: false, hasTrades: false } as T;
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', totalProfit: '0.00', unpricedPositions: 0 } } as T;
    if (path === '/api/market-quotes') return [] as T;
    if (path.includes('catalog-status')) return { state: 'READY', count: 5000 } as T;
    return { items: [], page: 0, size: 50, totalPages: 0, totalElements: 0, hasNext: false } as T;
  };
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><InvestmentsPage request={request} role="OWNER"/></QueryClientProvider>);
  return { user: userEvent.setup(), requests, setFail: (value: boolean) => { fail = value; } };
}
it('keeps overseas selection and charts read-only, currency-correct, and isolated when changing markets', async () => {
  const { user, requests } = setup();
  await user.click(screen.getByRole('button', { name: '行情' }));
  await user.click(screen.getByRole('button', { name: '美股' }));
  const select = await screen.findByRole('combobox', { name: '证券' });
  await waitFor(() => expect(select).toHaveAttribute('aria-disabled', 'false'));
  await user.click(select);
  await user.click(await screen.findByRole('option', { name: /AAPL.*Apple/ }));
  expect(await screen.findByText(/USD\s*319\.97/)).toBeInTheDocument();
  expect(screen.getByText('只读行情，暂不计入家庭资产')).toBeInTheDocument();
  const dialog=screen.getByRole('dialog',{name:'证券行情'});
  expect(within(dialog).queryByRole('button', { name: /记录买入|记录卖出|记一笔投资|录入已有持仓/ })).not.toBeInTheDocument();
  expect(within(dialog).queryByRole('region', { name: '投资初始化' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('复权方式')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '港股' }));
  expect(screen.queryByText(/USD\s*319\.97/)).not.toBeInTheDocument();
  await user.click(await screen.findByRole('combobox', { name: '证券' }));
  await user.click(await screen.findByRole('option', { name: /00700.*騰訊/ }));
  expect(await screen.findByText(/HKD\s*319\.97/)).toBeInTheDocument();
  expect(requests.every(r => !r.method || r.method === 'GET')).toBe(true);
  await user.click(screen.getByRole('button', { name: 'A 股' }));
  expect(screen.getByRole('button', { name: '记一笔投资' })).toBeInTheDocument();
});
it('keeps a stale published directory selectable while showing its refresh failure', async () => {
  const request: RequestFn = async <T,>() => ({ items: [hk], state: 'ERROR', error: 'timeout', updatedAt: '2026-09-07T12:00:00Z', stale: true, hasNext: false }) as T;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><OverseasMarketPanel request={request} market="HK"/></QueryClientProvider>);
  await screen.findByText('目录刷新失败，正在使用上次目录。');
  await userEvent.click(screen.getByRole('combobox', { name: '证券' }));
  expect(await screen.findByRole('option', { name: /00700.*騰訊/ })).toBeInTheDocument();
  view.unmount(); client.clear();
});
it('does not display a US instrument returned to the HK search', async () => {
  const request: RequestFn = async <T,>() => ({ items: [us], state: 'READY', error: null, updatedAt: '2026-09-08T12:00:00Z', stale: false, hasNext: false }) as T;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><OverseasMarketPanel request={request} market="HK"/></QueryClientProvider>);
  await screen.findByText('股票搜索暂时不可用');
  expect(screen.queryByText('Apple Inc.')).not.toBeInTheDocument();
  view.unmount(); client.clear();
});
it('polls only while preparing and stops automatic polling after three minutes', async () => {
  vi.useFakeTimers();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const request = vi.fn(async () => ({ items: [], state: 'SYNCING', error: null, updatedAt: null, stale: false, hasNext: false }));
  const view = render(<QueryClientProvider client={client}><OverseasMarketPanel request={request as RequestFn} market="US"/></QueryClientProvider>);
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(11_000); });
    expect(request.mock.calls.length).toBeGreaterThan(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(180_000); });
    const calls = request.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(request.mock.calls.length).toBe(calls);
    expect(screen.getByRole('button', { name: '重试目录' })).toBeInTheDocument();
  } finally {
    view.unmount(); client.clear(); vi.useRealTimers();
  }
});
it('shows a failed overseas search with retry rather than claiming an empty market', async () => {
  const { user, setFail } = setup();
  setFail(true);
  await user.click(screen.getByRole('button', { name: '行情' }));
  await user.click(screen.getByRole('button', { name: '港股' }));
  expect(await screen.findByText('股票搜索暂时不可用')).toBeInTheDocument();
  setFail(false);
  await user.click(screen.getByRole('button', { name: '重试搜索' }));
  await user.click(screen.getByRole('combobox', { name: '证券' }));
  expect(await screen.findByRole('option', { name: /00700.*騰訊/ })).toBeInTheDocument();
});
