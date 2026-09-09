import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DashboardPage } from './DashboardPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const request = vi.fn(async (path: string) => {
  if (path.startsWith('/api/dashboard')) return { summary: { income: '12800.00', expense: '4650.25', balance: '8149.75' }, daily: [], expenseByCategory: [], expenseByMember: [] };
  if (path === '/api/net-worth') return { asset: '560000.00', liability: '210000.00', netWorth: '350000.00', allocation: [], debtRatioPercent: '37.5', budget: { activeBudgetCount: 2, planned: '6000.00', spent: '4650.25', nearLimitCount: 1, overLimitCount: 0 }, investment: { marketValue: '32000.00', positionCount: 2, unpricedPositionCount: 0, manualPrice: false, stalePrice: true, missingPrice: false }, history: [] };
  if (path === '/api/debt-analysis') return { liability: '210000.00', asset: '560000.00', debtRatioPercent: '37.5', loans: [] };
  if (path === '/api/portfolio') return { positions: [], totals: { cost: '30000.00', marketValue: '32000.00', realizedProfit: '200.00', unrealizedProfit: '1800.00', totalProfit: '2000.00', unpricedPositions: 0 } };
  if (path === '/api/notifications') return { items: [], unreadCount: 2 };
  if (path === '/api/loans/debt-overview') return { count: 0, remainingPrincipal: '0.00', remainingRepayment: '0.00', thirtyDayDue: '0.00', paidRepayment: '0.00', weightedAnnualRatePercent: '0.00', nextDueOn: null, overdueInstallments: 0, overdueAmount: '0.00', overdueDays: 0 };
  throw new Error(`unexpected ${path}`);
});

it('renders authoritative dashboard values and stale market state', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><DashboardPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  expect(await screen.findByText('¥350,000.00')).toBeInTheDocument();
  expect(screen.getByText('¥8,149.75')).toBeInTheDocument();
  expect(screen.getByText('行情已过期')).toBeInTheDocument();
  expect(screen.queryByText('2 条未读')).not.toBeInTheDocument();
  expect(screen.queryByText('最近流水')).not.toBeInTheDocument();
  expect(screen.queryByText('费用预算执行')).not.toBeInTheDocument();
  expect(screen.queryByText('成员费用')).not.toBeInTheDocument();
  expect(screen.queryByText('累计资产估值变动')).not.toBeInTheDocument();
});

it('keeps detailed net worth history behind a dialog and avoids irrelevant home requests', async () => {
  request.mockClear();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><DashboardPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  await screen.findByText('¥350,000.00');
  expect(request.mock.calls.some(([path]) => path === '/api/debt-analysis' || path === '/api/notifications' || path.startsWith('/api/transactions'))).toBe(false);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '查看净资产详情' }));
  expect(screen.getByRole('dialog', { name: '净资产详情' })).toBeInTheDocument();
  expect(screen.getByText('资产配置')).toBeInTheDocument();
});

it('explains unknown allocation inside net worth details instead of presenting an empty asset list', async () => {
  const missingFx = (async (path: string) => {
    const result = await request(path);
    return path === '/api/net-worth' ? { ...result, asset: null, netWorth: null, allocation: [], unconverted: [{currency:'USD',nativeAmount:'100.00',kind:'ACCOUNT'}] } : result;
  }) as RequestFn;
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><DashboardPage request={missingFx} role="OWNER"/></QueryClientProvider>);
  await screen.findByText('汇率待补齐');
  await userEvent.click(screen.getByRole('button',{name:'查看净资产详情'}));
  const dialog = screen.getByRole('dialog',{name:'净资产详情'});
  expect(within(dialog).getByText('资产配置待补齐估值')).toBeInTheDocument();
  expect(within(dialog).getByRole('link',{name:'补充汇率'})).toHaveAttribute('href','/workspace/investments?tab=rates');
});

it('opens the selected overseas stock chart lazily and preserves native currency and unavailable profit', async () => {
  const marketRequest = vi.fn(async (path: string) => {
    if (path === '/api/portfolio') return { positions: [{ accountId: 9, accountName: '港股账户', securityId: 55, name: '小米集团－W', tsCode: '01810.HK', market: 'HK', symbol: '01810', exchange: 'HKEX', timezone: 'Asia/Hong_Kong', currency: 'HKD', quantity: 100, price: '26.38', marketValue: '2638.00', cost: '2500.00', averageCost: '25.00', unrealizedProfit: null, totalProfit: null, source: 'TENCENT', stale: true, base: { marketValue: '2400.00', fxState: 'READY' } }], totals: { marketValue: '2400.00', unrealizedProfit: null, totalProfit: null, unpricedPositions: 0 } };
    if (path.startsWith('/api/overseas-market/candles')) throw new Error('行情暂时不可用');
    return request(path);
  });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><DashboardPage request={marketRequest as RequestFn} role="OWNER" /></QueryClientProvider>);
  const stock = await screen.findByRole('button', { name: '查看小米集团－W行情' });
  expect(within(stock).getByText('HKD 26.38')).toBeInTheDocument();
  expect(within(stock).getByText('—')).toBeInTheDocument();
  expect(screen.queryByText('今日涨跌')).not.toBeInTheDocument();
  expect(marketRequest.mock.calls.some(([path]) => path.includes('/candles'))).toBe(false);
  await userEvent.click(stock);
  expect(await screen.findByRole('dialog', { name: '小米集团－W · 行情' })).toBeInTheDocument();
  expect(await screen.findByText('行情暂时不可用')).toBeInTheDocument();
  expect(marketRequest.mock.calls.some(([path]) => path === '/api/overseas-market/candles?market=HK&symbol=01810')).toBe(true);
});

it('offers one initialization action instead of repeated failing financial panels', async () => {
  const pending: RequestFn = async () => { throw new ApiError('需要初始化', { status: 409, code: 'ACCOUNTING_NOT_INITIALIZED' }); };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><DashboardPage request={pending} role="OWNER" /></QueryClientProvider>);
  expect(await screen.findByRole('link', { name: '去初始化账户' })).toHaveAttribute('href', '/workspace/transactions?section=accounts');
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(screen.queryByText('暂不可用')).not.toBeInTheDocument();
  fireEvent.click(screen.getByText('现金账户已确认，报表仍未完整？'));
  expect(screen.getByRole('link', {name: '核对资产记录'})).toHaveAttribute('href', '/workspace/assets');
  expect(screen.getByRole('link', {name: '核对贷款记录'})).toHaveAttribute('href', '/workspace/loans');
  expect(screen.getByRole('link', {name: '核对投资记录'})).toHaveAttribute('href', '/workspace/investments');
});

it('keeps the applied dashboard month when manual filter text is invalid or cleared', async () => {
  request.mockClear();
  const user = userEvent.setup();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><DashboardPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  await screen.findByText('¥350,000.00');

  const input = screen.getByRole('textbox', { name: '收支月份' });
  const appliedMonth = input.getAttribute('value');
  expect(screen.queryByRole('button', { name: '清除月份' })).not.toBeInTheDocument();

  await user.clear(input);
  await user.type(input, '2026-13');
  expect(screen.getByText(/请输入有效月份（YYYY-MM）/)).toBeInTheDocument();
  expect(request.mock.calls.filter(([path]) => String(path).startsWith('/api/dashboard?month=')).every(([path]) => String(path).includes(`month=${appliedMonth}`))).toBe(true);

  await user.tab();
  expect(input).toHaveValue(appliedMonth);
  expect(screen.queryByText(/请输入有效月份（YYYY-MM）/)).not.toBeInTheDocument();
});

it('carries the selected month when opening income and expense details', async () => {
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><DashboardPage request={request as RequestFn} role="OWNER"/></QueryClientProvider>);
  await screen.findByText('¥350,000.00');
  const input = screen.getByRole('textbox', {name:'收支月份'});
  await userEvent.clear(input);
  await userEvent.type(input, '2026-08');
  await userEvent.tab();
  expect(screen.getByRole('link',{name:'收支明细'})).toHaveAttribute('href','/workspace/transactions?month=2026-08');
});
