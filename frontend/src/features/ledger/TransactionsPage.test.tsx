import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionsPage } from './TransactionsPage';
import type { RequestFn } from '../common';

it('creates a transaction with selected server account category and member', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
    if (path.startsWith('/api/transactions') && options?.method === 'POST') return { id: 9 };
    if (path.startsWith('/api/transactions')) return [];
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '0.00', archivedAt: null }] };
    if (path.startsWith('/api/categories')) return [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }];
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(<QueryClientProvider client={client}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await screen.findByText('还没有收支记录');
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  const dialog = screen.getByRole('dialog', { name: '记一笔' });
  await user.type(within(dialog).getByLabelText('金额'), '68.50');
  await user.selectOptions(within(dialog).getByLabelText('账户'), '1');
  await user.selectOptions(within(dialog).getByLabelText('分类'), '2');
  await user.selectOptions(within(dialog).getByLabelText('成员'), '3');
  await user.type(within(dialog).getByLabelText('商家'), '社区食堂');
  await user.click(within(dialog).getByRole('button', { name: '保存收支' }));
  expect(request).toHaveBeenCalledWith('/api/transactions', expect.objectContaining({
    method: 'POST', body: expect.objectContaining({ amount: '68.50', accountId: 1, categoryId: 2, memberId: 3, merchant: '社区食堂' })
  }));
});
