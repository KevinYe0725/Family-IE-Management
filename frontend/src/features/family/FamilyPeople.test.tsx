import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect } from 'vitest';
import { FamilyPage } from './FamilyPage';
import type { RequestFn } from '../common';

const people = [
  { memberId: 1, membershipId: 10, name: 'Kevin', relationship: '爸爸', accountDisplayName: '演示用户', email: 'demo@test.local', role: 'OWNER', loginStatus: 'AVAILABLE' },
  { memberId: 2, membershipId: null, name: '奶奶', relationship: '长辈', accountDisplayName: null, email: null, role: null, loginStatus: 'NONE' },
  { memberId: 3, membershipId: 11, name: 'Lily', relationship: '妈妈', accountDisplayName: 'Lily', email: 'lily@test.local', role: 'MEMBER', loginStatus: 'AVAILABLE' },
  { memberId: 4, membershipId: 12, name: '暂停成员', relationship: null, accountDisplayName: '暂停成员', email: 'paused@test.local', role: 'MEMBER', loginStatus: 'SUSPENDED' },
];
function show(role: 'OWNER' | 'MEMBER' = 'OWNER') {
  const request = (async (path: string) => {
    if (path === '/api/family') return { id: 1, name: '一家人', status: 'ACTIVE' };
    if (path === '/api/family/people') return people;
    return { items: [], page: 0, totalPages: 0, totalElements: 0, hasNext: false, size: 50 };
  }) as RequestFn;
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><FamilyPage request={request} role={role} /></QueryClientProvider>);
}
describe('unified family directory', () => {
  it('shows offline family beside login users using the same ledger name and separates relationship from permission', async () => {
    show();
    const grandma = (await screen.findByRole('heading', { name: '奶奶' })).closest('article')!;
    expect(within(grandma).getByText('由家人代记')).toBeInTheDocument();
    expect(within(grandma).getByText('长辈')).toBeInTheDocument();
    expect(within(grandma).queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Kevin' })).toBeInTheDocument();
    expect(screen.getByText('账号昵称：演示用户')).toBeInTheDocument();
    expect(screen.getByText('4 位家人 · 2 人可登录')).toBeInTheDocument();
    const paused = screen.getByRole('heading', { name: '暂停成员' }).closest('article')!;
    expect(within(paused).getByText('登录已停用')).toBeInTheDocument();
    expect(within(paused).queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '设为管理员' })).toHaveLength(1);
  });
  it('does not offer permission changes to ordinary members', async () => {
    show('MEMBER');
    await screen.findByRole('heading', { name: '奶奶' });
    expect(screen.queryByRole('button', { name: '设为管理员' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '转让所有权' })).not.toBeInTheDocument();
  });
});
