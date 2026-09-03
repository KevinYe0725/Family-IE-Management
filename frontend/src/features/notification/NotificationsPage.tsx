import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { NotificationItem, NotificationPage } from '../../api/contracts';
import { DataPanel, PageScaffold, QueryState, StatusTag, dateText, type RequestFn } from '../common';

export function NotificationsPage({ request }: { request: RequestFn }) {
  const client = useQueryClient();
  const [filter, setFilter] = useState<'all' | 'unread' | 'open'>('all');
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: () => request<NotificationPage>('/api/notifications') });
  const update = useMutation({ mutationFn: ({ id, action }: { id: number; action: 'read' | 'resolve' }) => request<NotificationItem>(`/api/notifications/${id}/${action}`, { method: 'POST' }), onSuccess: () => client.invalidateQueries({ queryKey: ['notifications'] }) });
  const generate = useMutation({ mutationFn: () => request<number>('/api/notifications/generate', { method: 'POST' }), onSuccess: () => client.invalidateQueries({ queryKey: ['notifications'] }) });
  const items = notifications.data?.items.filter(item => filter === 'all' || filter === 'unread' ? filter === 'all' || !item.readAt : !item.resolvedAt) ?? [];
  const route = (item: NotificationItem) => item.referenceType.includes('LOAN') ? '/workspace/loans' : item.referenceType.includes('ASSET') ? '/workspace/assets' : item.referenceType.includes('RECURRING') ? '/workspace/recurring' : item.referenceType.includes('BUDGET') ? '/workspace/budgets' : '/workspace/overview';
  return <PageScaffold title="提醒中心" description="处理预算、周期账单、贷款、资产估值和行情提醒。">
    <div className="summary-strip notification-summary"><div><span>未读提醒</span><strong>{notifications.data?.unreadCount ?? '—'}</strong><small>来自服务器</small></div><div><span>待处理</span><strong>{notifications.data?.items.filter(item => !item.resolvedAt).length ?? '—'}</strong><small>当前页面记录</small></div><Button size="small" loading={generate.isPending} onClick={() => generate.mutate()}>刷新今日提醒</Button></div>
    <DataPanel title="全部提醒" action={<div className="pill-filter"><button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>全部</button><button className={filter === 'unread' ? 'active' : ''} onClick={() => setFilter('unread')}>未读</button><button className={filter === 'open' ? 'active' : ''} onClick={() => setFilter('open')}>待处理</button></div>}><QueryState loading={notifications.isLoading} error={notifications.error} empty={!items.length} emptyTitle="这里很清净" emptyDetail="当前筛选没有提醒。"><div className="notification-list">{items.map(item => <article className={!item.readAt ? 'unread' : ''} key={item.id}><i /><div><header><h3>{item.title}</h3><span>{dateText(item.dueAt)}</span></header><p>{item.body}</p><footer><StatusTag tone={item.resolvedAt ? 'success' : item.type.includes('LIMIT') || item.type.includes('DUE') ? 'warning' : 'blue'}>{item.resolvedAt ? '已处理' : '待处理'}</StatusTag><span>{!item.readAt && <button onClick={() => update.mutate({ id: item.id, action: 'read' })}>标为已读</button>}{!item.resolvedAt && <button onClick={() => update.mutate({ id: item.id, action: 'resolve' })}>标记完成</button>}<a href={route(item)}>查看来源</a></span></footer></div></article>)}</div></QueryState></DataPanel>
  </PageScaffold>;
}
