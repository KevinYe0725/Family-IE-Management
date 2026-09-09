import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PaymentPreview } from './accounting';
import { DashboardPage } from './dashboard/DashboardPage';
import type { Account } from '../api/contracts';
import type { RequestFn } from './common';

const account = { id: 1, name: '工资卡', type: 'BANK', currency: 'CNY', openingBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '0.00', availableBalance: '0.00', archivedAt: null } as Account;
it.each([['0.00', '1100.01', '1100.01'], ['1100.01', '1100.01', '0.00']])('shows exact available, payable and shortfall for %s paying %s', (balance, amount, gap) => {
  render(<PaymentPreview account={{ ...account, availableBalance: balance }} amount={amount} />);
  const preview = within(screen.getByRole('region', { name: '账务金额预览' }));
  expect(preview.getByText(`资金缺口 ¥${Number(gap).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`)).toBeInTheDocument();
  expect(preview.getByText('账内可用余额')).toBeInTheDocument();
  expect(preview.getByText('本次现金合计')).toBeInTheDocument();
});
it('does not assert a current shortfall for historical corrections', () => {
  render(<PaymentPreview account={account} amount="100.00" adjustment />);
  expect(screen.queryByText(/资金缺口/)).not.toBeInTheDocument();
  expect(screen.getByText(/更正会冲回原记录/)).toBeInTheDocument();
});
it('keeps yesterday cost estimates qualified in history rows and chart when today is quoted', async () => {
  const request = (async (path: string) => {
    if (path === '/api/net-worth') return { asset: '200.00', liability: '0.00', netWorth: '200.00', allocation: [], budget: { activeBudgetCount: 0 }, investment: { missingPrice: false, unpricedPositionCount: 0 }, history: [
      { snapshotOn: '2026-01-03', asset: '100.00', liability: '0.00', netWorth: '100.00', accountingBasis: 'LEDGER_AS_OF', valuationEstimated: true, unpricedPositions: 1 },
      { snapshotOn: '2026-01-04', asset: '200.00', liability: '0.00', netWorth: '200.00', accountingBasis: 'LEDGER_AS_OF', valuationEstimated: false, unpricedPositions: 0 }
    ] };
    if (path.startsWith('/api/dashboard')) return { summary: {}, daily: [], expenseByMember: [] };
    if (path === '/api/notifications') return { items: [], unreadCount: 0 };
    if (path === '/api/debt-analysis') return { loans: [] };
    if (path === '/api/portfolio') return { totals: {} };
    return [];
  }) as RequestFn;
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><DashboardPage request={request} role="OWNER" /></QueryClientProvider>);
  await screen.findByText(/2026-01-03：.*含成本估算.*1/);
  fireEvent.click(screen.getByRole('button', { name: '查看净资产详情' }));
  const yesterday = await screen.findByRole('row', { name: /2026-01-03/ , hidden: true });
  expect(within(yesterday).getByText(/含成本估算.*1/)).toBeInTheDocument();
  expect(within(yesterday).getByText(/按生效日期重算/)).toBeInTheDocument();
  expect(within(screen.getByRole('row', { name: /2026-01-04/, hidden: true })).queryByText(/含成本估算/)).not.toBeInTheDocument();
  expect(screen.getByText(/2026-01-03：.*含成本估算.*1/)).toBeInTheDocument();
});
