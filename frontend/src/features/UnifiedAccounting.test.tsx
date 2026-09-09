import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionsPage } from './ledger/TransactionsPage';
import { InvestmentsPage } from './investment/InvestmentsPage';
import type { RequestFn } from './common';
import { ApiError } from '../api/client';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const cash = { id: 1, name: '工资卡', type: 'BANK', currency: 'CNY', openingBalance: '100.30', openingConfirmed: true, openingOn: '2026-01-01', balance: '100.30', availableBalance: '100.30', archivedAt: null };
const generated = { id: 8, kind: 'expense', amount: '1100.00', occurredOn: '2026-09-01', accountId: 1, accountName: '工资卡', memberId: 2, memberName: '本人', createdByUserId: 7, createdByName: '本人', sourceType: 'LOAN_PAYMENT', sourceId: 9, principalAmount: '1000.00', interestAmount: '100.00', categoryId: 3, categoryName: '利息', categoryParentId: null, categoryLevel: 1, merchant: null, location: null, note: null, createdAt: '', updatedAt: '' };
function setup(options: { accounts?: unknown[]; transactions?: unknown[]; write?: (path: string, opts: any) => unknown } = {}, investment = false) {
  const request = vi.fn(async (path: string, opts?: any) => {
    if(path==='/api/bank-accounts')return [];
    if (opts?.method) return options.write?.(path, opts) ?? { id: 1 };
    if (path.startsWith('/api/accounts')) return page(options.accounts ?? [cash]);
    if (path.startsWith('/api/transactions')) return page(options.transactions ?? []);
    if (path.startsWith('/api/categories')) return page([{ id: 3, name: '利息', kind: 'expense', level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 2, name: '本人' }];
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    if (path === '/api/market-quotes') return [];
    return page([]);
  });
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cache}>{investment ? <InvestmentsPage request={request as RequestFn} role="OWNER" /> : <TransactionsPage request={request as RequestFn} role="OWNER" userId={7} />}</QueryClientProvider>);
  return { request, cache, user: userEvent.setup() };
}
it('offers explicit zero opening with a date and does not present unknown balance as zero', async () => {
  const { user, request } = setup({ accounts: [{ ...cash, openingConfirmed: false, openingOn: null, balance: null, availableBalance: null }] });
  await user.click(screen.getByRole('button', { name: '账户' }));
  expect(await screen.findByRole('button', { name: '归档' })).toBeDisabled();
  await user.click(await screen.findByRole('button', { name: '确认期初余额' }));
  const form = screen.getByRole('dialog');
  fireEvent.change(within(form).getByLabelText('期初余额'), { target: { value: '0.00' } });
  fireEvent.change(within(form).getByLabelText('账务起始日期'), { target: { value: '2026-01-01' } });
  await user.click(within(form).getByRole('checkbox', { name: /确认以上期初余额/ }));
  await user.click(within(form).getByRole('button', { name: '保存账户' }));
  expect(request).toHaveBeenCalledWith('/api/accounts/1', expect.objectContaining({ body: { openingBalance: '0.00', openingOn: '2026-01-01' }, headers: { 'Idempotency-Key': expect.any(String) } }));
});
it('shows exact available cash and remaining cents, preserves the draft and key on a rejected retry', async () => {
  const { user, request } = setup({ write: () => { throw new ApiError('余额不足，请刷新后核对', { status: 409, code: 'INSUFFICIENT_FUNDS' }); } });
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  await user.selectOptions(within(screen.getByRole('dialog')).getByLabelText('账户'), '1');
  await user.type(screen.getByLabelText('金额'), '100.31');
  expect(await screen.findByText('预计余额 -¥0.01')).toBeInTheDocument();
  expect(screen.getByText(/账内可用余额不足/)).toBeInTheDocument();
  fireEvent.submit(screen.getByLabelText('金额').closest('form')!);
  await screen.findByText('余额不足，请刷新后核对');
  fireEvent.submit(screen.getByLabelText('金额').closest('form')!);
  await waitFor(() => expect(request.mock.calls.filter(([, opts]) => opts?.method === 'POST')).toHaveLength(2));
  const writes = request.mock.calls.filter(([, opts]) => opts?.method === 'POST');
  expect(writes[0][1].headers['Idempotency-Key']).toBeTruthy();
  expect(writes[0][1].headers).toEqual(writes[1][1].headers);
  expect(screen.getByLabelText('金额')).toHaveValue('100.31');
  expect(request.mock.calls.filter(([path]) => path.startsWith('/api/accounts')).length).toBeGreaterThan(2);
});
it('limits a generated payment edit to metadata and displays its principal and interest', async () => {
  const { user, request } = setup({ transactions: [generated] });
  await user.click((await screen.findAllByRole('button', { name: '编辑' }))[0]);
  expect(screen.getByLabelText('金额')).toBeDisabled();
  expect(screen.getAllByText(/本金.*1,000.00/).length).toBeGreaterThan(0);
  await user.type(screen.getByLabelText('备注'), '核对完成');
  await user.click(screen.getByRole('button', { name: '保存收支' }));
  expect(request).toHaveBeenCalledWith('/api/transactions/8', expect.objectContaining({ body: { merchant: null, location: null, note: '核对完成' } }));
});
it('requires a cash funding account for a new investment account', async () => {
  const { user } = setup({}, true);
  await user.click(screen.getByRole('button',{name:'投资管理'}));
  await user.click(await screen.findByRole('menuitem',{name:'账户'}));
  await user.click(await screen.findByRole('button', { name: '新建账户' }));
  expect(await screen.findByLabelText('资金账户')).toBeRequired();
  expect(screen.getByRole('option', { name: /工资卡/ })).toBeInTheDocument();
});
