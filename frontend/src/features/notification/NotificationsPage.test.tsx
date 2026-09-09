import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NotificationsPage } from './NotificationsPage';
import type { RequestFn } from '../common';

it('opens investment plan reminders at the actionable plan tab',async()=>{
 const request=(async()=>({unreadCount:1,items:[{id:7,title:'定投到期',body:'请确认实际成交',type:'INVESTMENT_PLAN_DUE',referenceType:'INVESTMENT_PLAN_OCCURRENCE',referenceId:9,dueAt:'2026-09-09',readAt:null,resolvedAt:null}]})) as RequestFn;
 render(<QueryClientProvider client={new QueryClient()}><NotificationsPage request={request} role="ADMIN"/></QueryClientProvider>);
 expect(await screen.findByRole('link',{name:'查看来源'})).toHaveAttribute('href','/workspace/investments?tab=plans');
});

it.each(['OWNER', 'ADMIN', 'MEMBER'] as const)('keeps read/resolve and gates generation for %s', async role => {
  const writes: string[] = [];
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') { writes.push(path); return {} as T; }
    return { unreadCount: 1, items: [{ id: 1, title: '待办', body: '提醒内容', type: 'BUDGET_LIMIT', referenceType: 'BUDGET', dueAt: '2026-09-01', readAt: null, resolvedAt: null }] } as T;
  };
  render(<QueryClientProvider client={new QueryClient()}><NotificationsPage request={request} role={role} /></QueryClientProvider>);
  await screen.findByText('待办');
  expect(Boolean(screen.queryByRole('button', { name: '刷新今日提醒' }))).toBe(role !== 'MEMBER');
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '标为已读' }));
  await user.click(screen.getByRole('button', { name: '标记完成' }));
  expect(writes).toEqual(['/api/notifications/1/read', '/api/notifications/1/resolve']);
});
