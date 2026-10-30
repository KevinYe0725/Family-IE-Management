import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionsPage } from './TransactionsPage';
import type { RequestFn } from '../common';

const page = <T,>(items: T[], current = 0, total = items.length) => ({ items, page: current, size: 50,
  totalElements: total, totalPages: Math.ceil(total / 50), hasNext: (current + 1) * 50 < total });
const item = (id: number, sourceType = 'MANUAL') => ({
  id, kind: 'expense', amount: '10.00', occurredOn: '2026-10-12', accountId: 1,
  accountName: '合成账户', memberId: 3, memberName: '合成成员', categoryId: 2, categoryName: '合成分类',
  categoryParentId: null, categoryLevel: 1, merchant: null, location: null,
  note: sourceType === 'RECURRING' ? '周期测试记录' : '手工测试记录', createdByUserId: 7,
  createdByName: '合成用户', sourceType, createdAt: '', updatedAt: ''
});

it('applies source to list, summary and CSV, resets page, and restores all', async () => {
  const originalUrl = window.location.href;
  window.history.replaceState({}, '', '/workspace/transactions?month=2026-10');
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions/summary')) {
      const source = new URLSearchParams(path.split('?')[1]).get('sourceType');
      return { currency: 'CNY', income: '0.00', expense: source === 'RECURRING' ? '10.00' : '510.00',
        balance: '-10.00', transactionCount: source === 'RECURRING' ? 1 : 51, unconvertedCount: 0,
        nonCashTransactionCount: 0, categories: [], daily: [] };
    }
    if (path.startsWith('/api/transactions?')) {
      const params = new URLSearchParams(path.split('?')[1]);
      if (params.get('sourceType') === 'RECURRING') return page([item(100, 'RECURRING')]);
      if (params.get('sourceType') === 'LOAN') return page([]);
      return params.get('page') === '1' ? page([item(51)], 1, 51)
        : page(Array.from({ length: 50 }, (_, index) => item(index + 1)), 0, 51);
    }
    if (path.startsWith('/api/accounts')) return page([{ id: 1, name: '合成账户', type: 'CASH', currency: 'CNY',
      openingBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '0.00', availableBalance: '0.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return page([{ id: 2, kind: 'expense', name: '合成分类', color: '#3370FF',
      defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 3, name: '合成成员', roleLabel: '成员', createdAt: '' }];
    if (path === '/api/currencies') return { currencies: ['CNY'] };
    throw new Error(`unexpected request ${path}`);
  });
  try {
    const user = userEvent.setup();
    render(<QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}>
      <TransactionsPage request={request as RequestFn} role="MEMBER" userId={7}/>
    </QueryClientProvider>);
    await screen.findByRole('table');
    const select = screen.getByRole('combobox', {name: '来源筛选'});
    expect(within(select).getAllByRole('option').map(option => (option as HTMLOptionElement).value))
      .toEqual(['', 'MANUAL', 'RECURRING', 'LOAN', 'LOAN_PAYMENT', 'LOAN_PREPAYMENT']);
    await user.selectOptions(screen.getByRole('combobox', {name: '成员筛选'}), '3');
    await user.click(await screen.findByRole('button', {name: '下一页'}));
    await screen.findByText('第 2 / 2 页');
    await user.selectOptions(select, 'RECURRING');
    await screen.findAllByText('周期测试记录');
    await waitFor(() => {
      expect(request.mock.calls.some(([path]) => {
        if (!path.startsWith('/api/transactions?')) return false;
        const params = new URLSearchParams(path.split('?')[1]);
        return params.get('sourceType') === 'RECURRING' && params.get('page') === '0'
          && params.get('memberId') === '3' && params.get('month') === '2026-10';
      })).toBe(true);
      expect(request.mock.calls.some(([path]) => {
        if (!path.startsWith('/api/transactions/summary?')) return false;
        const params = new URLSearchParams(path.split('?')[1]);
        return params.get('sourceType') === 'RECURRING' && params.get('memberId') === '3';
      })).toBe(true);
    });
    expect(request.mock.calls.some(([path]) => path.startsWith('/api/transactions?')
      && path.includes('sourceType=RECURRING') && path.includes('page=1'))).toBe(false);
    const exportParams = new URL(screen.getByRole('link', {name: '导出 CSV'}).getAttribute('href')!, window.location.origin).searchParams;
    expect(exportParams.get('sourceType')).toBe('RECURRING');
    expect(exportParams.get('memberId')).toBe('3');
    expect(screen.queryByText('第 2 / 2 页')).not.toBeInTheDocument();
    await user.selectOptions(select, 'LOAN');
    expect(await screen.findByText('还没有收支记录')).toBeInTheDocument();
    expect(screen.getByRole('link', {name: '导出 CSV'}).getAttribute('href')).toContain('sourceType=LOAN');
    await user.selectOptions(select, '');
    await screen.findAllByText('手工测试记录');
    const allParams = new URL(screen.getByRole('link', {name: '导出 CSV'}).getAttribute('href')!, window.location.origin).searchParams;
    expect(allParams.has('sourceType')).toBe(false);
    expect(allParams.get('memberId')).toBe('3');
  } finally { window.history.replaceState({}, '', originalUrl); }
});

it('clears the source filter after a new transaction is saved', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path === '/api/transactions' && options?.method === 'POST') return { id: 99 };
    if (path.startsWith('/api/transactions/summary')) return { currency: 'CNY', income: '0.00', expense: '0.00',
      balance: '0.00', transactionCount: 0, unconvertedCount: 0, categories: [], daily: [] };
    if (path.startsWith('/api/transactions?')) return page([]);
    if (path.startsWith('/api/accounts')) return page([{ id: 1, name: '合成账户', type: 'CASH', currency: 'CNY',
      openingBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '0.00', availableBalance: '0.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return page([{ id: 2, kind: 'expense', name: '合成分类', color: '#3370FF',
      defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 3, name: '合成成员', roleLabel: '成员', createdAt: '' }];
    if (path === '/api/currencies') return { currencies: ['CNY'] };
    if (path.startsWith('/api/budgets/hit-check')) return [];
    throw new Error(`unexpected request ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}>
    <TransactionsPage request={request as RequestFn} role="OWNER" userId={7}/>
  </QueryClientProvider>);
  await screen.findByText('还没有收支记录');
  const select = screen.getByRole('combobox', {name: '来源筛选'});
  await user.selectOptions(select, 'RECURRING');
  await user.click(screen.getByRole('button', {name: '记一笔'}));
  const dialog = screen.getByRole('dialog', {name: '记一笔'});
  await user.type(within(dialog).getByLabelText('金额'), '10');
  await user.selectOptions(within(dialog).getByLabelText('分类'), '2');
  await user.click(within(dialog).getByRole('button', {name: '保存收支'}));
  await waitFor(() => expect(select).toHaveValue(''));
});
