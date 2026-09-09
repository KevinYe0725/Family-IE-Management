import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import type { RequestFn } from '../common';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const wrap = (ui: React.ReactElement) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>;

it('cancels a cash-purchased asset through a confirmation dialog', async () => {
  const asset = { id: 4, name: '收藏', type: 'OTHER', accountingMode: 'PURCHASE', accountingOn: '2026-01-01', acquiredOn: '2026-01-01', purchaseValue: '2000.00', currentValue: '2000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, ownerMemberId: null, property: null, vehicle: null };
  const request = vi.fn(async (path: string, opts?: { method?: string }) => {
    if (opts?.method === 'POST') return {};
    if (path.startsWith('/api/assets')) return page([asset]);
    if (path === '/api/members') return [];
    if (path === '/api/net-worth') return { asset: '2000.00' };
    return page([]);
  });
  const user = userEvent.setup();
  render(wrap(<AssetsPage request={request as RequestFn} role="OWNER" />));
  await user.click((await screen.findAllByRole('button', { name: '取消' }))[0]);
  expect(screen.getByText(/将红字冲销该资产的取得与估值账务/)).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '取消资产' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/assets/4/cancel', expect.objectContaining({ method: 'POST', headers: { 'Idempotency-Key': expect.any(String) } })));
});

it('routes a financed-purchase asset cancellation to its linked loan', async () => {
  const asset = { id: 8, name: '车辆1', type: 'VEHICLE', accountingMode: 'FINANCED_PURCHASE', accountingOn: '2026-01-01', acquiredOn: '2026-01-01', purchaseValue: '2000.00', currentValue: '2000.00', status: 'ACTIVE', createdBy: 7, archivedAt: null, ownerMemberId: null, property: null, vehicle: null, acquisitionSourceType: 'LOAN_FINANCED_PURCHASE', acquisitionSourceId: 4, detailsPending: true };
  const loan = { id: 4, name: '车贷', type: 'CAR', linkedAssetId: 8, currentPrincipal: '2000.00', status: 'ACTIVE' };
  const request = vi.fn(async (path: string, opts?: { method?: string }) => {
    if (opts?.method === 'POST') return {};
    if (path.startsWith('/api/assets')) return page([asset]);
    if (path.startsWith('/api/loans')) return page([loan]);
    if (path === '/api/members') return [];
    if (path === '/api/net-worth') return { asset: '2000.00' };
    return page([]);
  });
  const user = userEvent.setup();
  render(wrap(<AssetsPage request={request as RequestFn} role="OWNER" />));
  await user.click((await screen.findAllByRole('button', { name: '取消' }))[0]);
  expect(screen.getByText(/取消将一并撤销关联贷款计划/)).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '取消资产' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/loans/4/cancel', expect.objectContaining({ method: 'POST' })));
});
