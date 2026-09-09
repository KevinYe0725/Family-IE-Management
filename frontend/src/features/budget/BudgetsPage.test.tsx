import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BudgetsPage } from './BudgetsPage';
import type { RequestFn } from '../common';

const budgetUsage = (id: number, active: boolean) => ({ budget: { id, periodMonth: '2026-09', scopeType: 'CATEGORY' as const, categoryId: 1, memberId: null, amount: '100.00', version: 0, active, note: null }, spent: '0.00', remaining: '100.00', percent: 0, status: 'ON_TRACK' as const, rollupCategories: true });

it('reaches an inactive budget beyond a full first page of active budgets', async () => {
  const active = Array.from({ length: 50 }, (_, index) => budgetUsage(index + 1, true));
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/budgets/usage')) {
      if (path.includes('active=false')) return { items: [budgetUsage(51, false)], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
      return { items: active, page: 0, size: 50, totalElements: 51, totalPages: 2, hasNext: true };
    }
    if (path.startsWith('/api/budgets/total')) return { periodMonth: '2026-09', amount: null, version: 0 };
    if (path.startsWith('/api/dashboard')) return { summary: {} };
    if (path.startsWith('/api/categories')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><BudgetsPage request={request as RequestFn} role="MEMBER" /></QueryClientProvider>);

  await user.click(await screen.findByRole('button', { name: '已停用' }));
  expect(await screen.findAllByText('已停用')).toHaveLength(2);
  expect(request).toHaveBeenCalledWith(expect.stringContaining('active=false'), { responseType: 'page' });
});
