import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { DashboardPage } from './DashboardPage';
import type { RequestFn } from '../common';

const request = vi.fn(async (path: string) => {
  if (path.startsWith('/api/dashboard')) return { summary: { income: '12800.00', expense: '4650.25', balance: '8149.75' }, daily: [], expenseByCategory: [], expenseByMember: [] };
  if (path === '/api/net-worth') return { asset: '560000.00', liability: '210000.00', netWorth: '350000.00', allocation: [], debtRatioPercent: '37.5', budget: { activeBudgetCount: 2, planned: '6000.00', spent: '4650.25', nearLimitCount: 1, overLimitCount: 0 }, investment: { marketValue: '32000.00', positionCount: 2, unpricedPositionCount: 0, manualPrice: false, stalePrice: true, missingPrice: false }, history: [] };
  if (path === '/api/debt-analysis') return { liability: '210000.00', asset: '560000.00', debtRatioPercent: '37.5', loans: [] };
  if (path === '/api/portfolio') return { positions: [], totals: { cost: '30000.00', marketValue: '32000.00', realizedProfit: '200.00', unrealizedProfit: '1800.00', totalProfit: '2000.00', unpricedPositions: 0 } };
  if (path === '/api/notifications') return { items: [], unreadCount: 2 };
  throw new Error(`unexpected ${path}`);
});

it('renders authoritative dashboard values and stale market state', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><DashboardPage request={request as RequestFn} role="OWNER" displayName="凯文" /></QueryClientProvider>);
  expect(await screen.findByText('¥350,000.00')).toBeInTheDocument();
  expect(screen.getByText('¥8,149.75')).toBeInTheDocument();
  expect(screen.getByText('行情已过期')).toBeInTheDocument();
  expect(screen.getByText('2 条未读')).toBeInTheDocument();
});
