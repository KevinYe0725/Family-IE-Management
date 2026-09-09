import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { QueryState } from './common';
import { ApiError } from '../api/client';
import { refreshAfterWrite } from '../shared/write-refresh';
import { PortfolioSummary } from './investment/PortfolioSummary';

it('makes accounting initialization actionable without showing a zero report', () => {
  render(<QueryState loading={false} error={new ApiError('历史记录未初始化', { status: 409, code: 'ACCOUNTING_NOT_INITIALIZED' })}><strong>¥0.00</strong></QueryState>);
  expect(screen.getByRole('link', { name: '去确认账户期初余额' })).toHaveAttribute('href', '/workspace/transactions?section=accounts');
  expect(screen.queryByText('¥0.00')).not.toBeInTheDocument();
  expect(screen.getByText(/历史资产、贷款或投资记录/)).toBeInTheDocument();
});
it('refreshes cash, budgets and source history after investment and asset money writes', async () => {
  for (const path of ['/api/investment-trades', '/api/assets/4/dispose', '/api/transfers']) {
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    for (const key of ['accounts', 'budget-usage', 'accounting-history', 'transfers']) cache.setQueryData([key], 1);
    await refreshAfterWrite(cache, path, { method: 'POST' }, () => true);
    expect(cache.getQueryState(['accounts'])?.isInvalidated).toBe(true);
    expect(cache.getQueryState(['budget-usage'])?.isInvalidated).toBe(true);
    expect(cache.getQueryState(['accounting-history'])?.isInvalidated).toBe(true);
  }
});
it('identifies unpriced cost estimates and does not report an incomplete market total as known', () => {
  render(<PortfolioSummary portfolio={{ positions: [], totals: { cost: '100.00', estimatedValue: '100.00', marketValue: null, totalProfit: null, realizedProfit: '5.00', unrealizedProfit: null, unpricedPositions: 1 } }} onViewQuotes={() => {}} />);
  expect(screen.getByText(/含成本估算/)).toBeInTheDocument();
  expect(screen.getByText(/市值与浮动收益尚不完整/)).toBeInTheDocument();
});

it('refreshes budget totals and hit previews after budget and template writes', async () => {
 for(const path of ['/api/budgets/total','/api/budget-templates/2/apply']) {
  const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});
  for(const key of ['budget-total','budget-usage','budget-entries','budget-hit'])cache.setQueryData([key],1);
  await refreshAfterWrite(cache,path,{method:'POST'},()=>true);
  for(const key of ['budget-total','budget-usage','budget-entries','budget-hit'])expect(cache.getQueryState([key])?.isInvalidated).toBe(true);
 }
});
