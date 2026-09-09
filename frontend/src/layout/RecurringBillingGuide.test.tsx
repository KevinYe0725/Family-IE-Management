import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { Session } from '../api/contracts';
import type { ApiRequest } from '../api/client';
import { RecurringBillingGuide } from './RecurringBillingGuide';

const session: Session = {
  userId: 7,
  householdId: 11,
  email: 'demo@example.com',
  displayName: '演示用户',
  role: 'OWNER',
  username: 'demo@example.com',
};

function renderGuide(request: ApiRequest) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/workspace/overview']}>
      <RecurringBillingGuide session={session} request={request} />
    </MemoryRouter>
  </QueryClientProvider>);
}

it('shows pending recurring bills after login and opens the recurring workspace', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/recurring-occurrences')) return { items: [{ id: 3, ruleId: 1, dueOn: '2026-09-01', status: 'PENDING', assignedUserId: 7, confirmedTransactionId: null }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    return { items: [{ id: 1, kind: 'expense', amount: '200.00', active: true, paused: false, categoryName: '餐饮', assignedUserName: '演示用户' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
  });
  renderGuide(request as ApiRequest);

  expect(await screen.findByRole('dialog', { name: '有待确认的周期账单' })).toBeInTheDocument();
  expect(screen.getByText('检测到 1 条待确认账单')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: '家庭预算记录场景' })).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: '前去确认' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: '有待确认的周期账单' })).not.toBeInTheDocument());
});

it('still reminds about active rules when no bill is due yet', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/recurring-occurrences')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    return { items: [{ id: 1, kind: 'income', amount: '10000.00', active: true, paused: false, categoryName: '工资', assignedUserName: '演示用户' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
  });
  renderGuide(request as ApiRequest);

  expect(await screen.findByRole('dialog', { name: '周期账单规则仍在运行' })).toBeInTheDocument();
  expect(screen.getByText('有 1 条周期规则正在运行')).toBeInTheDocument();
});
