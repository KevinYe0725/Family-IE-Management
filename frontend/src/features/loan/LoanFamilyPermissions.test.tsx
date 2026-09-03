import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { LoansPage } from './LoansPage';
import { FamilyPage } from '../family/FamilyPage';
import type { RequestFn } from '../common';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;

it('keeps financial management read-only for members and owner controls exclusive', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/loans')) return { items: [] };
    if (path === '/api/family') return { id: 1, name: '凯文之家', status: 'ACTIVE', archivedAt: null };
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 2, userId: 8, email: 'member@example.com', displayName: '成员', role: 'MEMBER', status: 'ACTIVE' }] };
    if (path.startsWith('/api/family/invites')) return { items: [], page: 0, size: 20, hasNext: false };
    throw new Error(`unexpected ${path}`);
  });
  const first = render(wrap(<LoansPage request={request as RequestFn} role="MEMBER" />));
  expect(await screen.findByText('当前为只读协作视图')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '新建贷款' })).not.toBeInTheDocument();
  first.unmount();
  render(wrap(<FamilyPage request={request as RequestFn} role="OWNER" householdName="凯文之家" />));
  expect(await screen.findByRole('button', { name: '归档家庭' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '邀请成员' })).toBeInTheDocument();
});
