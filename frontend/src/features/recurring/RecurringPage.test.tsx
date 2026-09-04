import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RecurringPage } from './RecurringPage';
import type { RequestFn } from '../common';

it('allows monthly rules to use the 31st and relies on the server for month-end clamping', async () => {
  const request = vi.fn(async (path: string) => {
    if (path === '/api/recurring-rules?includeInactive=true&page=0&size=50') return [];
    if (path === '/api/recurring-occurrences?status=PENDING&page=0&size=50') return [];
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常账户', type: 'BANK', currency: 'CNY', openingBalance: '0.00', archivedAt: null }] };
    if (path.startsWith('/api/categories')) return [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }];
    if (path === '/api/members') return [{ id: 3, name: 'Kevin', roleLabel: '本人', createdAt: '' }];
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 4, userId: 7, email: 'demo@example.com', displayName: '演示用户', role: 'OWNER', status: 'ACTIVE' }] };
    throw new Error(`unexpected ${path}`);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(<QueryClientProvider client={client}><RecurringPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '新建周期规则' }));
  expect(screen.getByLabelText('每月日期')).toHaveAttribute('max', '31');
});
