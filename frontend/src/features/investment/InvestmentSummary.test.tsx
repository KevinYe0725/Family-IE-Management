import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentsPage } from './InvestmentsPage';
import type { RequestFn } from '../common';

function renderPortfolio(unpricedPositions: number) {
  const request = (async (path: string) => {
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '80.00', marketValue: unpricedPositions ? null : '100.00', estimatedValue: '100.00', realizedProfit: '5.00', unrealizedProfit: '15.00', totalProfit: '20.00', unpricedPositions } };
    if (path === '/api/market-quotes') return [];
    return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
  }) as RequestFn;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><InvestmentsPage request={request} role="MEMBER"/></QueryClientProvider>);
}

it('hides summary amounts without replacing unknown values with fake numbers',async()=>{
 renderPortfolio(0);
 await screen.findByText('¥100.00');
 await userEvent.click(screen.getByRole('button',{name:'隐藏汇总金额'}));
 expect(screen.queryByText('¥100.00')).not.toBeInTheDocument();
 expect(screen.queryByText('¥20.00')).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'显示汇总金额'}));
 expect(screen.getByText('¥100.00')).toBeInTheDocument();
});

it('keeps valuation guidance available on demand without a zero-price warning card', async () => {
  renderPortfolio(0);
  const user = userEvent.setup();
  expect(await screen.findByText('¥100.00')).toBeInTheDocument();
  expect(screen.queryByText('缺少价格')).not.toBeInTheDocument();
  await user.click(screen.getByLabelText('市值口径'));
  expect(screen.getByText('按有效行情计算。缺价持仓保留成本估算，组合市值与浮动收益暂未知。')).toBeVisible();
  await user.keyboard('{Escape}');
  expect(screen.getByText('按有效行情计算。缺价持仓保留成本估算，组合市值与浮动收益暂未知。')).not.toBeVisible();
});

it('keeps a meaningful missing-price warning and a working route to quotes', async () => {
  renderPortfolio(2);
  const user = userEvent.setup();
  await screen.findByText(/含成本估算的组合价值/);
  expect(await screen.findByRole('status')).toHaveTextContent('2 项持仓缺少价格，市值与浮动收益尚不完整。');
  await user.click(screen.getByRole('button', { name: '查看行情' }));
  expect(screen.getByRole('heading', { name: '收盘行情' })).toBeInTheDocument();
});
