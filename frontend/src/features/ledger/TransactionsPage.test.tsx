import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TransactionsPage } from './TransactionsPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const pageResult = <T,>(items: T[], current = 0, total = items.length) => ({
  items, page: current, size: 50, totalElements: total, totalPages: total === 0 ? 0 : Math.ceil(total / 50), hasNext: (current + 1) * 50 < total
});

it('does not count an existing transaction again in the new-expense budget preview', async () => {
 const request=vi.fn(async(path:string)=>{
  if(path.startsWith('/api/transactions'))return pageResult([{id:7,kind:'expense',amount:'100.00',occurredOn:'2026-09-09',accountId:1,accountName:'银行卡',memberId:2,memberName:'Kevin',categoryId:3,categoryName:'餐饮',createdByUserId:7,createdByName:'Kevin',sourceType:'MANUAL'}]);
  if(path==='/api/members')return [{id:2,name:'Kevin'}];
  if(path.startsWith('/api/accounts'))return pageResult([{id:1,name:'银行卡',type:'BANK',currency:'CNY',openingConfirmed:true,balance:'1000.00',availableBalance:'1000.00'}]);
  if(path.startsWith('/api/budgets/hit-check'))return [{budgetId:1,statusAfter:'OVER_BUDGET',categoryName:'餐饮',percentAfter:'110',spent:'100.00',scopeType:'CATEGORY'}];
  return pageResult([]);
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
 await userEvent.click((await screen.findAllByRole('button',{name:'编辑'}))[0]);
 await screen.findByRole('dialog',{name:'编辑收支'});
 expect(request.mock.calls.some(([path])=>path.startsWith('/api/budgets/hit-check'))).toBe(false);
 expect(screen.queryByRole('status',{name:'预算影响提醒'})).not.toBeInTheDocument();
});

it('opens one empty entry from the homepage shortcut without submitting anything', async () => {
  const original = window.location.href;
  window.history.replaceState({}, '', '/workspace/transactions?create=1');
  const request = vi.fn(async (path: string) => path === '/api/members' ? [] : pageResult([]));
  try {
    render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
    expect(await screen.findByRole('dialog', {name:'记一笔'})).toBeInTheDocument();
    expect(screen.getByLabelText('金额')).toHaveValue('');
    expect(window.location.search).not.toContain('create=1');
    await userEvent.click(screen.getByRole('button',{name:'关闭'}));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  } finally { window.history.replaceState({}, '', original); }
});

it('retains a failed draft, links its field error, and clears errors on reopen', async () => {
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') throw new ApiError('保存失败', { status: 400, fields: { amount: '金额必须大于零' } });
    if (path === '/api/members') return [] as T;
    return pageResult([]) as T;
  };
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient()}><TransactionsPage request={request} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  await user.type(screen.getByLabelText('金额'), '28.50');
  fireEvent.submit(screen.getByLabelText('金额').closest('form')!);
  await screen.findByText('金额必须大于零');
  expect(screen.getByLabelText('金额')).toHaveValue('28.50');
  expect(screen.getByLabelText('金额')).toHaveAccessibleDescription('金额必须大于零');
  expect(screen.getByLabelText('金额')).toHaveFocus();
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('button', { name: '放弃修改' }));
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  expect(screen.queryByText('金额必须大于零')).not.toBeInTheDocument();
  expect(screen.getByLabelText('金额')).toHaveValue('');
});

it('keeps a pending save in its form session and closes successfully without a discard prompt', async () => {
  let complete!: () => void;
  const pending = new Promise<void>(resolve => { complete = resolve; });
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') { await pending; return { id: 9 } as T; }
    if (path === '/api/members') return [] as T;
    return pageResult([]) as T;
  };
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient()}><TransactionsPage request={request} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  await user.type(screen.getByLabelText('金额'), '28.50');
  fireEvent.submit(screen.getByLabelText('金额').closest('form')!);
  expect(await screen.findByRole('button', { name: '关闭' })).toBeDisabled();
  expect(screen.getByLabelText('金额')).toBeDisabled();
  await user.keyboard('{Escape}');
  fireEvent.mouseDown(screen.getByRole('dialog').parentElement!);
  expect(screen.getByLabelText('金额')).toHaveValue('28.50');
  expect(screen.queryByRole('dialog', { name: '放弃未保存的修改？' })).not.toBeInTheDocument();
  await act(async () => complete());
  await screen.findByRole('button', { name: '记一笔' });
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  expect(screen.getByLabelText('金额')).toHaveValue('');
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('creates a transaction with selected server account category and member', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
    if (path.startsWith('/api/transactions') && options?.method === 'POST') return { id: 9 };
    if (path.startsWith('/api/transactions')) return pageResult([]);
    if (path.startsWith('/api/accounts')) return pageResult([{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '1000.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '1000.00', availableBalance: '1000.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return pageResult([{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(<QueryClientProvider client={client}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await screen.findByText('还没有收支记录');
  await user.click(screen.getByRole('button', { name: '记一笔' }));
  const dialog = screen.getByRole('dialog', { name: '记一笔' });
  expect(within(dialog).getByLabelText('账户')).toHaveValue('1');
  expect(within(dialog).getByLabelText('成员')).toHaveValue('3');
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
    sourceType: 'MANUAL', createdAt: '', updatedAt: ''
  };
}

it('does not preselect an account whose opening date is missing', async () => {
  const request = async (path: string) => {
    if (path.startsWith('/api/accounts')) return pageResult([{id: 1, name: '待核对账户', type: 'CASH', currency: 'CNY', openingConfirmed: true, openingOn: null, availableBalance: '10.00', balance: '10.00', openingBalance: '10.00', archivedAt: null}]);
    if (path === '/api/members') return [];
    return pageResult([]);
  };
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
  await screen.findByText('还没有收支记录');
  await user.click(screen.getByRole('button', {name: '记一笔'}));
  expect(within(screen.getByRole('dialog', {name: '记一笔'})).getByRole('combobox', {name: '账户'})).toHaveValue('');
});

it('shows creator, paginates transactions, and links a complete csv export', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions')) {
      const page = new URLSearchParams(path.split('?')[1]).get('page');
      return page === '1' ? { items: [transactionItem(51)], page: 1, size: 50, totalElements: 51, totalPages: 2, hasNext: false } : pageResult(Array.from({ length: 50 }, (_, index) => transactionItem(index + 1)), 0, 51);
    }
    if (path.startsWith('/api/accounts')) return pageResult([{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '1000.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '1000.00', availableBalance: '1000.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return pageResult([{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  expect((await screen.findAllByText('演示用户')).length).toBeGreaterThan(0);
  expect(screen.getByRole('link', { name: '导出 CSV' })).toHaveAttribute('href', '/api/export.csv?month=2026-09&kind=expense');
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect((await screen.findAllByText('-¥10.00')).length).toBeGreaterThan(0);
  expect(request).toHaveBeenCalledWith(expect.stringContaining('page=1'), { responseType: 'page' });
});

it('keeps a backend permission error visible when permissions change after rendering', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path.startsWith('/api/transactions') && options?.method === 'DELETE') {
      throw new ApiError('无权操作他人创建的收支记录', { status: 403, code: 'FORBIDDEN' });
    }
    if (path.startsWith('/api/transactions')) return pageResult([transactionItem(1)]);
    if (path.startsWith('/api/accounts')) return pageResult([{ id: 1, name: '日常银行卡', type: 'BANK', currency: 'CNY', openingBalance: '1000.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '1000.00', availableBalance: '1000.00', archivedAt: null }]);
    if (path.startsWith('/api/categories')) return pageResult([{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }]);
    if (path === '/api/members') return [{ id: 3, name: '凯文', roleLabel: '本人', createdAt: '' }];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="MEMBER" userId={7} /></QueryClientProvider>);
  await user.click((await screen.findAllByRole('button', { name: '删除' }))[0]);
  await user.click(screen.getByRole('button', { name: '删除收支' }));
  expect(await screen.findByText('无权操作他人创建的收支记录')).toBeInTheDocument();
});

it.each([
  ['OWNER', 9, 'MANUAL', 2, 2], ['ADMIN', 9, 'MANUAL', 2, 2],
  ['MEMBER', 7, 'MANUAL', 2, 2], ['MEMBER', 9, 'MANUAL', 0, 0],
  ['OWNER', 9, 'RECURRING', 2, 0], ['ADMIN', 9, 'LOAN_PAYMENT', 2, 0],
  ['MEMBER', 7, 'LOAN_PREPAYMENT', 2, 0], ['MEMBER', 9, 'RECURRING', 0, 0]
] as const)('gates desktop and mobile transaction actions for %s user %i source %s', async (role, userId, sourceType, edits, deletes) => {
  const request: RequestFn = async <T,>(path: string) => {
    if (path.startsWith('/api/transactions')) return pageResult([{ ...transactionItem(1), sourceType }]) as T;
    if (path === '/api/members') return [] as T;
    return pageResult([]) as T;
  };
  render(<QueryClientProvider client={new QueryClient()}><TransactionsPage request={request} role={role} userId={userId} /></QueryClientProvider>);
  await screen.findByRole('table');
  expect(screen.queryAllByRole('button', { name: '编辑' })).toHaveLength(edits);
  expect(screen.queryAllByRole('button', { name: '删除' })).toHaveLength(deletes);
});

it('paginates category roots without losing stable parent-child order', async () => {
  const roots = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, kind: 'expense', name: `分类-${index + 1}`, color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }));
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions')) return pageResult([]);
    if (path.startsWith('/api/accounts')) return pageResult([]);
    if (path.startsWith('/api/categories')) return path.includes('page=1') ? pageResult([{ ...roots[0], id: 51, name: '分类-51' }], 1, 51) : pageResult(roots, 0, path.includes('projection=tree') ? 51 : 50);
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '分类' }));
  await user.click(screen.getByRole('button', { name: '下一页' }));
  expect(await screen.findByText('分类-51')).toBeInTheDocument();
  expect(request).toHaveBeenCalledWith(expect.stringContaining('projection=tree&page=1'), { responseType: 'page' });
});

it('offers a parent category from a later reference page', async () => {
  const roots = Array.from({ length: 50 }, (_, index) => ({ id: index + 1, kind: 'expense', name: `分类-${index + 1}`, color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }));
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/transactions') || path.startsWith('/api/accounts')) return pageResult([]);
    if (path.includes('/api/categories') && path.includes('projection=flat')) return path.includes('page=1') ? pageResult([{ ...roots[0], id: 51, name: '跨页父分类' }], 1, 51) : pageResult(roots, 0, 51);
    if (path.includes('/api/categories')) return pageResult(roots, 0, 51);
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><TransactionsPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '分类' }));
  await user.click(screen.getByRole('button', { name: '新建分类' }));

  expect(await within(screen.getByRole('dialog', { name: '新建分类' })).findByRole('option', { name: '跨页父分类' })).toBeInTheDocument();
});
