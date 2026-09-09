import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
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
  expect(screen.getByText('2 条未读')).toBeInTheDocument();
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
