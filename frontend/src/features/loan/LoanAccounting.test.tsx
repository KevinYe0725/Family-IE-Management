import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage } from './LoansPage';
import { ApiError } from '../../api/client';
import type { RequestFn } from '../common';
const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const loan = { id: 4, name: '房贷', type: 'OTHER', assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, principal: '2000.00', currentPrincipal: '2000.00', annualRate: '0.1', repaymentMethod: 'CUSTOM', termMonths: 2, startOn: '2026-01-01', fundingMode: 'OPENING', accountingOn: '2026-01-01', accountingInitialized: true, lastPaymentOn: null, status: 'ACTIVE' };
function setup(userId = 7, overrides: Partial<typeof loan> = {}) {
  let current = { ...loan, ...overrides };
  const request = vi.fn(async (path: string, opts?: any) => {
    if (opts?.method === 'POST') { current = { ...current, currentPrincipal: '1000.00', paymentAccountId: 3 }; return { id: 1 }; }
    if (path === '/api/loans/4') return current;
    if (path.startsWith('/api/loans?')) return page([current]);
    if (path.includes('/schedule')) return page([{ id: 9, installmentNo: 1, dueOn: '2026-01-02', principal: '1000.00', interest: '100.00', cashAmount: '1100.00', status: current.status === 'CLOSED' ? 'PAID' : current.status === 'ARCHIVED' ? 'CANCELLED' : 'PENDING', paidOn: current.status === 'CLOSED' ? '2026-01-03' : null }]);
    if (path.startsWith('/api/accounts')) return page([{ id: 1, name: '工资卡', openingConfirmed: true, openingOn: '2026-01-01', balance: '1200.30', availableBalance: '1200.30', archivedAt: null }, { id: 3, name: '新扣款卡', openingConfirmed: true, openingOn: '2026-01-01', balance: '20.00', availableBalance: '20.00', archivedAt: null }]);
    if (path === '/api/members') return [];
    return page([]);
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cache}><LoansPage request={request as RequestFn} role="OWNER" userId={userId} /></QueryClientProvider>);
  return { user: userEvent.setup(), request, cache };
}
it('previews principal plus interest and records an actual payment date with a stable command key', async () => {
  const { user, request } = setup();
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  await user.click(await screen.findByRole('button', { name: '确认还款' }));
  expect(await screen.findByText('预计余额 ¥100.30')).toBeInTheDocument();
  const date = screen.getByLabelText('实际还款日期');
  fireEvent.change(date, { target: { value: '2026-01-03' } });
  await user.selectOptions(screen.getByLabelText('本次付款账户'), '3');
  await user.click(screen.getByRole('button', { name: '记录本期还款' }));
  expect(request).toHaveBeenCalledWith('/api/loan-installments/9/confirm', expect.objectContaining({ body: { paidOn: '2026-01-03', paymentAccountId: 3 }, headers: { 'Idempotency-Key': expect.any(String) } }));
  await waitFor(() => expect(request.mock.calls.filter(([path]) => path === '/api/loans/4').length).toBeGreaterThan(1));
});
it('does not give an owner an override for another assigned user', async () => {
  const { user } = setup(8);
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  await screen.findByText('待确认');
  expect(screen.queryByRole('button', { name: '确认还款' })).not.toBeInTheDocument();
});
it('offers closed loan history and explicit opening versus disbursement creation', async () => {
  const { user } = setup();
  expect(screen.getByLabelText('贷款状态')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '新建贷款' }));
  expect(screen.getByLabelText('入账方式')).toHaveValue('FINANCED_PURCHASE');
  expect(screen.getByLabelText('贷款购买本金')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('入账方式'), 'OPENING');
  expect(screen.getByLabelText('账务起始日剩余本金')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('入账方式'), 'DISBURSEMENT');
  expect(screen.getByLabelText('放款到账账户')).toBeRequired();
});
it('locks contract type and labels the final action as saving a correction', async () => {
  const { user } = setup();
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  await user.click(await screen.findByRole('button', { name: '更正未付款合同' }));
  expect(screen.getByLabelText('贷款类型')).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '下一步' }));
  await user.click(screen.getByRole('button', { name: '下一步' }));
  expect(screen.getByRole('button', { name: '保存合同更正' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '创建并生成计划' })).not.toBeInTheDocument();
});
it.each([
  { status: 'CLOSED', accountingInitialized: true },
  { status: 'ARCHIVED', accountingInitialized: true },
  { status: 'ACTIVE', accountingInitialized: false }
])('keeps $status initialized=$accountingInitialized loan history free of unsupported future-settings writes', async overrides => {
  const { user } = setup(7, overrides);
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  await screen.findByRole('button', { name: '贷款起始账务历史' });
  expect(screen.queryByRole('button', { name: '未来还款设置' })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '贷款起始账务历史' })).toBeInTheDocument();
});
