import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage } from './LoansPage';
import type { RequestFn } from '../common';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
function setup(financed = false) {
  const loan = { id: 4, name: '购房贷款', type: 'MORTGAGE', principal: '1000.00', currentPrincipal: '1000.00', annualRate: '0.06', termMonths: 2, repaymentMethod: 'EQUAL_PAYMENT', startOn: '2026-01-01', accountingOn: '2026-01-01', fundingMode: 'FINANCED_PURCHASE', purchasedAssetId: 8, linkedAssetId: 8, memberId: null, assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, accountingInitialized: true, lastPaymentOn: null, status: 'ACTIVE' };
  const request = vi.fn(async (path: string, opts?: any) => {
    if (opts?.method) return loan;
    if (path === '/api/loans/4') return loan;
    if (path.startsWith('/api/loans?')) return page(financed ? [loan] : []);
    if (path.startsWith('/api/accounts')) return page([{ id: 1, name: '扣款卡', openingConfirmed: true, openingOn: '2026-01-01', balance: '0.00', availableBalance: '0.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return page([{ id: 2, name: '还款利息', kind: 'expense' }]);
    if (path.startsWith('/api/family/memberships')) return page([{ userId: 7, displayName: '本人', status: 'ACTIVE' }]);
    if (path.startsWith('/api/assets')) return page([{ id: 8, name: '房产1', type: 'PROPERTY' }, { id: 9, name: '旧车', type: 'VEHICLE' }]);
    if (path === '/api/members') return [];
    return page([]);
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cache}><LoansPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  return { user: userEvent.setup(), request };
}
it('creates a purchased asset from the association selector with a direct-purchase payload and clear cash explanation', async () => {
  const { user, request } = setup();
  await user.click(screen.getByRole('button', { name: '新建贷款' }));
  await user.selectOptions(screen.getByLabelText('入账方式'), 'OPENING');
  await user.type(screen.getByLabelText('贷款名称'), '购房贷款');
  await user.type(screen.getByLabelText('账务起始日剩余本金'), '1000.00');
  await user.click(screen.getByRole('button', { name: '下一步' }));
  await user.type(screen.getByLabelText('年利率（%）'), '6');
  await user.clear(screen.getByLabelText('剩余计划期限（月）'));await user.type(screen.getByLabelText('剩余计划期限（月）'), '2');
  await user.click(screen.getByRole('button', { name: '下一步' }));
  await user.selectOptions(screen.getByLabelText('关联资产'), 'PURCHASED');
  expect(screen.getByText(/贷款人直接支付购买款，不经过家庭现金/)).toBeInTheDocument();
  expect(screen.queryByRole('option', { name: '旧车' })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('确认还款人'), '7');
  await user.selectOptions(screen.getByLabelText('扣款账户'), '1');
  await user.selectOptions(screen.getByLabelText('还款分类'), '2');
  await user.click(screen.getByRole('button', { name: '创建并生成计划' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/loans', expect.objectContaining({ method: 'POST', headers: { 'Idempotency-Key': expect.any(String) }, body: {
    name: '购房贷款', type: 'MORTGAGE', principal: '1000.00', repaymentMethod: 'EQUAL_PAYMENT', startOn: expect.any(String), accountingOn: expect.any(String),
    fundingMode: 'FINANCED_PURCHASE', createPurchasedAsset: true, disbursementAccountId: null, linkedAssetId: null, memberId: null, assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, annualRate: 0.06, termMonths: 2, customSchedule: null
  } })));
});
it('keeps financed origination fields disabled and omits them from a rate correction', async () => {
  const { user, request } = setup(true);
  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  await user.click(screen.getByRole('button', { name: '更正未付款合同' }));
  expect(screen.getByLabelText('贷款购买本金')).toBeDisabled();
  expect(screen.getByLabelText('购买入账日期')).toBeDisabled();
  expect(screen.getByLabelText('起息 / 计划起算日期')).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '下一步' }));
  await user.clear(screen.getByLabelText('年利率（%）'));await user.type(screen.getByLabelText('年利率（%）'), '5');
  await user.click(screen.getByRole('button', { name: '下一步' }));
  expect(screen.getByLabelText('关联资产')).toBeDisabled();
  await user.click(screen.getByRole('button', { name: '保存合同更正' }));
  await waitFor(() => expect(request.mock.calls.some(([, opts]) => opts?.method === 'PATCH')).toBe(true));
  const sent = request.mock.calls.find(([, opts]) => opts?.method === 'PATCH')![1].body;
  expect(sent.annualRate).toBe(0.05);
  for (const field of ['principal','startOn','accountingOn','linkedAssetId','disbursementAccountId','fundingMode','createPurchasedAsset']) expect(sent).not.toHaveProperty(field);
});
