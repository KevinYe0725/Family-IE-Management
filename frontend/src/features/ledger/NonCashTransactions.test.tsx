import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Transaction } from '../../api/contracts';
import type { RequestFn } from '../common';
import { LedgerCharts, type LedgerSummary } from './LedgerCharts';
import { BankTransactionsDialog, TransactionDetailsDialog } from './TransactionDetailsDialog';
import { TransactionsPage } from './TransactionsPage';

const page = (items: Transaction[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const cashSummary: LedgerSummary = {
  currency: 'CNY', income: '0.00', expense: '10.00', balance: '-10.00', transactionCount: 2,
  unconvertedCount: 0, categories: [{ categoryId: 2, name: '餐饮', color: '#3370FF', kind: 'expense', amount: '10.00', count: 1 }],
  daily: [{ date: '2026-09-09', kind: 'expense', categoryId: 2, amount: '10.00', count: 1 }]
};
const ordinary: Transaction = {
  id: 1, kind: 'expense', amount: '10.00', currency: 'CNY', occurredOn: '2026-09-09',
  accountId: 5, accountName: '日常银行卡', memberId: 3, memberName: 'Kevin', createdByUserId: 7,
  createdByName: 'Kevin', sourceType: 'MANUAL', sourceId: null, categoryId: 2, categoryName: '餐饮',
  categoryParentId: null, categoryLevel: 1, merchant: '普通消费', location: null, note: null,
  createdAt: '2026-09-09T00:00:00Z', updatedAt: '2026-09-09T00:00:00Z'
};
const direct: Transaction = {
  ...ordinary, id: 2, sourceType: 'LOAN_PREPAYMENT', sourceId: 8, amount: '1050.00',
  principalAmount: '1000.00', interestAmount: '50.00', categoryId: 9, categoryName: '贷款还款',
  merchant: '售房结清', cashImpact: false, settlementAssetId: 42
};

function provider(content: ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{content}</QueryClientProvider>;
}

it('keeps direct repayments readonly in desktop and mobile records without claiming bank withdrawals', async () => {
  const request: RequestFn = async <T,>(path: string) => {
    if (path.startsWith('/api/transactions/summary')) return cashSummary as T;
    if (path.startsWith('/api/transactions?')) return page([ordinary, direct]) as T;
    if (path === '/api/members') return [] as T;
    return page([]) as T;
  };
  render(provider(<TransactionsPage request={request} role="OWNER" userId={7} />));
  const row = await screen.findByRole('row', { name: /售房结清/ });
  const mobile = screen.getAllByText('售房结清').map(item => item.closest('article')).find(item => item !== null)!;
  for (const record of [row, mobile]) {
    expect(within(record).getByText('非现金还款')).toBeInTheDocument();
    expect(within(record).getByText(/买方代偿/)).toBeInTheDocument();
    expect(record).not.toHaveTextContent('日常银行卡');
    expect(record).not.toHaveTextContent(/[-−+]¥1,050\.00/);
    expect(within(record).queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(within(record).queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
  }
  const cash = screen.getByRole('row', { name: /普通消费/ });
  expect(cash).toHaveTextContent('-¥10.00');
  expect(within(cash).getByRole('button', { name: '编辑' })).toBeInTheDocument();
  expect(within(cash).getByRole('button', { name: '删除' })).toBeInTheDocument();
});

it('shows buyer settlement in repayment details while retaining access to accounting history', async () => {
  const onHistory = vi.fn();
  render(<TransactionDetailsDialog item={direct} onClose={() => {}} onHistory={onHistory} />);
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('买方代偿');
  expect(dialog).toHaveTextContent('不另行扣减银行账户余额');
  expect(dialog).not.toHaveTextContent('日常银行卡');
  expect(dialog).not.toHaveTextContent('−¥1,050.00');
  expect(dialog).toHaveTextContent('本金 ¥1,000.00 · 利息 ¥50.00');
  await userEvent.click(within(dialog).getByRole('button', { name: '账务历史' }));
  expect(onHistory).toHaveBeenCalledOnce();
});

it.each([undefined, null, true])('treats legacy cash flag %s as an ordinary bank transaction', cashImpact => {
  const item = JSON.parse(JSON.stringify({ ...ordinary, cashImpact })) as Transaction;
  render(<TransactionDetailsDialog item={item} onClose={() => {}} onHistory={() => {}} />);
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('日常银行卡');
  expect(dialog).toHaveTextContent('−¥10.00');
  expect(dialog).not.toHaveTextContent('买方代偿');
});

it('labels noncash records even when a bank context filter includes them', async () => {
  const request: RequestFn = async <T,>() => page([ordinary, direct]) as T;
  render(provider(<BankTransactionsDialog request={request} bankId={5} onClose={() => {}} />));
  const row = await screen.findByRole('row', { name: /售房结清/ });
  expect(row).toHaveTextContent('买方代偿');
  expect(row).not.toHaveTextContent('−¥1,050.00');
  expect(screen.getByRole('row', { name: /普通消费/ })).toHaveTextContent('−¥10.00');
});

it('shows cash-only chart amounts and identifies excluded direct repayment records', async () => {
  const request: RequestFn = async <T,>() => ({ ...cashSummary, nonCashTransactionCount: 1 }) as T;
  render(provider(<LedgerCharts request={request} filters="month=2026-09" kind="expense" selectedDay={null} onKind={() => {}} onCategory={() => {}} onDay={() => {}} />));
  expect(await screen.findByText(/1 笔买方代偿未计入现金收支/)).toBeInTheDocument();
  expect(screen.getByRole('img', { name: '支出分类：餐饮 100.0%' })).toBeInTheDocument();
  expect(screen.queryByText(/1,050/)).not.toBeInTheDocument();
});
