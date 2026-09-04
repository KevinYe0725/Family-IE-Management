import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import { InvestmentsPage } from '../investment/InvestmentsPage';
import { securityResolvePayload } from '../investment/InvestmentsPage';
import type { RequestFn } from '../common';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;

it('shows server asset values and quote provenance without member mutations', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/assets')) return { items: [{ id: 4, name: '滨江小家', type: 'PROPERTY', ownerMemberId: 1, acquiredOn: '2021-05-01', purchaseValue: '2600000.00', currentValue: '2850000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, property: { address: '滨江区', areaSqm: 89, usageType: '自住' }, vehicle: null }] };
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return { items: [] };
    if (path.startsWith('/api/investment-trades')) return { items: [] };
    if (path === '/api/market-quotes') return [{ securityId: 8, tsCode: '600000.SH', name: '浦发银行', price: '10.25', source: 'MANUAL', tradeDate: '2026-08-31', fetchedAt: null, stale: true, error: null }];
    throw new Error(`unexpected ${path}`);
  });
  const { unmount } = render(wrap(<AssetsPage request={request as RequestFn} role="MEMBER" />));
  expect((await screen.findAllByText('¥2,850,000.00')).length).toBeGreaterThan(0);
  expect(screen.queryByRole('button', { name: '新建资产' })).not.toBeInTheDocument();
  unmount();
  render(wrap(<InvestmentsPage request={request as RequestFn} role="MEMBER" />));
  await userEvent.click(screen.getByRole('button', { name: '行情' }));
  expect(await screen.findByText('手工价格')).toBeInTheDocument();
  expect(screen.getByText('行情已过期')).toBeInTheDocument();
});

it('offers security registration when an investment search has no matches', async () => {
  const request = vi.fn(async (path: string) => {
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path.startsWith('/api/investment-accounts')) return { items: [{ id: 1, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null }] };
    if (path.startsWith('/api/investment-trades')) return { items: [] };
    if (path === '/api/market-quotes') return [];
    if (path.startsWith('/api/securities/search')) return { items: [] };
    throw new Error(`unexpected ${path}`);
  });
  render(wrap(<InvestmentsPage request={request as RequestFn} role="OWNER" />));

  await userEvent.click(await screen.findByRole('button', { name: '记一笔投资' }));
  expect(await screen.findByRole('button', { name: '登记证券' })).toBeInTheDocument();
});

it('normalizes a six-digit A-share code with the selected market', () => {
  expect(securityResolvePayload({ code: ' 000001 ', market: 'SZ', name: '平安银行' }))
    .toEqual({ tsCode: '000001.SZ', name: '平安银行' });
});
