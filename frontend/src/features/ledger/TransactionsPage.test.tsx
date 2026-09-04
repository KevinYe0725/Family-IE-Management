import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionsPage } from './TransactionsPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

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

function transactionItem(id: number, creator = '演示用户') {
  return {
    id, kind: 'expense', amount: '10.00', occurredOn: '2026-09-01', accountId: 1,
    accountName: '日常银行卡', memberId: 3, memberName: '凯文', categoryId: 2,
    categoryName: '餐饮', categoryParentId: null, categoryLevel: 1, merchant: '商家',
    location: null, note: null, createdByUserId: 7, createdByName: creator,
    createdAt: '', updatedAt: ''
  };
}

it('shows creator, paginates transactions, and links a complete csv export', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions')) {
      const page = new URLSearchParams(path.split('?')[1]).get('page');
      return page === '1' ? [transactionItem(51)] : Array.from({ length: 50 }, (_, index) => transactionItem(index + 1));
    }
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '0.00', archivedAt: null }] };
    if (path.startsWith('/api/categories')) return [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }];
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  expect((await screen.findAllByText('演示用户')).length).toBeGreaterThan(0);
  expect(screen.getByRole('link', { name: '导出 CSV' })).toHaveAttribute('href', '/api/export.csv?month=2026-09');
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect((await screen.findAllByText('-¥10.00')).length).toBeGreaterThan(0);
  expect(request).toHaveBeenCalledWith(expect.stringContaining('page=1'));
});

it('keeps a permission error visible when deleting another member transaction is denied', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path.startsWith('/api/transactions') && options?.method === 'DELETE') {
      throw new ApiError('无权操作他人创建的收支记录', { status: 403, code: 'FORBIDDEN' });
    }
    if (path.startsWith('/api/transactions')) return [transactionItem(1, '其他成员')];
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '0.00', archivedAt: null }] };
    if (path.startsWith('/api/categories')) return [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }];
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="MEMBER" userId={9} /></QueryClientProvider>);
  await user.click((await screen.findAllByRole('button', { name: '删除' }))[0]);
  await user.click(screen.getByRole('button', { name: '删除收支' }));
  expect(await screen.findByText('无权操作他人创建的收支记录')).toBeInTheDocument();
});

it('paginates category roots without losing stable parent-child order', async () => {
  const roots = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, kind: 'expense', name: `分类-${index + 1}`, color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }));
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions')) return [];
    if (path.startsWith('/api/accounts')) return { items: [] };
    if (path.startsWith('/api/categories')) return path.includes('page=1') ? [{ ...roots[0], id: 51, name: '分类-51' }] : roots;
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '分类' }));
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('分类-51')).toBeInTheDocument();
  expect(request).toHaveBeenCalledWith(expect.stringContaining('projection=tree&page=1'));
});
