import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RecurringPage } from './RecurringPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';
it('keeps long category labels inside the chart while preserving their accessible names',async()=>{
 const name='非常长的家庭教育支出分类名称';
 const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
 const request=(async(path:string)=>path==='/api/members'?[]:page(path.startsWith('/api/recurring-rules')?[{id:1,categoryId:1,categoryName:name,kind:'expense',amount:'100.00',active:true,paused:false,scheduleType:'MONTHLY',intervalValue:1}]:[])) as RequestFn;
 const {container}=render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><RecurringPage request={request} role="OWNER" userId={7}/></QueryClientProvider>);
 expect(await screen.findByRole('img',{name:`支出分类：${name} 100.0%`})).toBeInTheDocument();
 const label=container.querySelector('.recurring-category-ring-label')!;
 const first=label.querySelector('tspan')!;
 expect(Array.from(first.textContent??'').length).toBeLessThanOrEqual(6);
 const x=Number(first.getAttribute('x')),width=Number(first.getAttribute('textLength'));
 expect(width).toBeGreaterThan(0);expect(width).toBeLessThanOrEqual(72);
 expect(label.getAttribute('text-anchor')==='end'?x-width:x).toBeGreaterThanOrEqual(0);
 expect(label.getAttribute('text-anchor')==='end'?x:x+width).toBeLessThanOrEqual(620);
});

it('previews the actual recurring cash payment before recording today without changing the due date', async () => {
  const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1, hasNext: false });
  const request = vi.fn(async (path: string, options?: any) => {
    if (options?.method === 'POST') return {};
    if (path.startsWith('/api/recurring-rules')) return page([{ id: 1, amount: '0.20', kind: 'expense', accountId: 2, accountName: '零钱', categoryName: '水费', assignedUserId: 7, active: true, paused: false, scheduleType: 'MONTHLY', intervalValue: 1, confirmationToken:'review-v1' }]);
    if (path.startsWith('/api/recurring-occurrences')) return page([{ id: 3, ruleId: 1, dueOn: '2026-01-01', assignedUserId: 7, status: 'PENDING' }]);
    if (path.startsWith('/api/accounts')) return page([{ id: 2, name: '零钱', openingConfirmed: true, balance: '0.30', availableBalance: '0.30', openingOn: '2026-01-01' }]);
    return path === '/api/members' ? [] : page([]);
  });
  const { container } = render(<QueryClientProvider client={new QueryClient()}><RecurringPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
  const user = userEvent.setup();
  const confirmButton = await screen.findByRole('button', { name: '确认入账' });
  const taskActions = container.querySelector('.task-actions');
  const taskList = container.querySelector<HTMLElement>('.task-list');
  expect(taskActions).not.toBeNull();
  expect(taskList).not.toBeNull();
  expect(taskActions?.querySelectorAll('button')).toHaveLength(2);
  expect(screen.getByRole('heading', { name: '支出分类' })).toBeInTheDocument();
  await waitFor(() => expect(container.querySelector('.recurring-category-ring')).not.toBeNull());
  expect(screen.getByRole('img', { name: '支出分类：水费 100.0%' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '月度计划现金流' })).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '执行节奏' })).not.toBeInTheDocument();
  expect(within(taskList!).queryByText('支出', { exact: true })).not.toBeInTheDocument();
  expect(within(taskList!).getByText('2026年1月1日')).toBeInTheDocument();
  expect(within(taskList!).queryByText('到期日', { exact: true })).not.toBeInTheDocument();
  expect(within(taskList!).queryByText(/逾期\s+\d+\s+天/)).not.toBeInTheDocument();
  await user.click(confirmButton);
  expect(screen.getByRole('heading', { name: '账单详情' })).toBeInTheDocument();
  expect(screen.getAllByText('零钱').length).toBeGreaterThan(0);
  expect(screen.getByRole('button', { name: '跳过本期' })).toBeInTheDocument();
  expect(screen.getByLabelText('本期实际金额')).toHaveValue('0.20');
  expect(screen.queryByText('仅预览本系统账本余额；记录不会执行银行或券商转账。')).not.toBeInTheDocument();
  await user.clear(screen.getByLabelText('本期实际金额'));
  await user.type(screen.getByLabelText('本期实际金额'), '0.25');
  expect(await screen.findByText('预计余额 ¥0.05')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '记录本次账单' }));
  expect(request).toHaveBeenCalledWith('/api/recurring-occurrences/3/confirm', { method: 'POST', body: { amount: '0.25', confirmationToken:'review-v1' } });
});

it.each([false,true])('keeps the reviewed rule snapshot when cached rules change, batch=%s',async(batch)=>{
 const rule={id:1,amount:'12.00',kind:'expense',accountId:2,accountName:'原付款账户',categoryName:'餐饮',assignedUserId:7,active:true,paused:false,scheduleType:'MONTHLY',intervalValue:1,confirmationToken:'review-v1'};
 const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
 const request=vi.fn(async(path:string,options?:any)=>{
  if(options?.method==='POST')return {};
  if(path.startsWith('/api/recurring-rules'))return page([rule]);
  if(path.startsWith('/api/recurring-occurrences'))return page([{id:3,ruleId:1,dueOn:'2026-01-01',assignedUserId:7,status:'PENDING'}]);
  if(path.startsWith('/api/accounts'))return page([{id:2,name:'原付款账户',openingConfirmed:true,availableBalance:'100.00',openingOn:'2026-01-01'}]);
  return path==='/api/members'?[]:page([]);
 });
 const client=new QueryClient({defaultOptions:{queries:{retry:false}}});
 render(<QueryClientProvider client={client}><RecurringPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
 await screen.findByRole('button',{name:'确认入账'});
 if(batch)await userEvent.click(screen.getByRole('checkbox',{name:'选择 2026-01-01'}));
 else await userEvent.click(screen.getByRole('button',{name:'确认入账'}));
 await act(async()=>{client.setQueryData(['recurring-rules','all-reference'],[{...rule,kind:'income',accountId:9,accountName:'未经核对的新账户',confirmationToken:'review-v2'}]);});
 if(batch){
  await userEvent.click(screen.getByRole('button',{name:'批量确认 1 条'}));
  expect(request).toHaveBeenCalledWith('/api/recurring-occurrences/confirm',{method:'POST',body:{occurrenceIds:[3],confirmationTokens:{3:'review-v1'}}});
 }else{
  const dialog=screen.getByRole('dialog',{name:'账单详情'});
  expect(within(dialog).queryByText('未经核对的新账户')).not.toBeInTheDocument();
  expect(within(dialog).getAllByText('原付款账户').length).toBeGreaterThan(0);
  await userEvent.click(within(dialog).getByRole('button',{name:'记录本次账单'}));
  expect(request).toHaveBeenCalledWith('/api/recurring-occurrences/3/confirm',{method:'POST',body:{amount:'12.00',confirmationToken:'review-v1'}});
 }
});

it('requires a fresh review after the server rejects a stale confirmation',async()=>{
 let rule={id:1,amount:'12.00',kind:'expense',accountId:2,accountName:'原付款账户',categoryName:'餐饮',assignedUserId:7,active:true,paused:false,scheduleType:'MONTHLY',intervalValue:1,confirmationToken:'review-v1'};
 const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
 const request=vi.fn(async(path:string,options?:any)=>{
  if(options?.method==='POST'){
   if(options.body.confirmationToken==='review-v1'){
    rule={...rule,kind:'income',accountId:9,accountName:'新收款账户',categoryName:'奖金',confirmationToken:'review-v2'};
    throw new ApiError('账单已变更',{status:409,code:'RECURRING_CONFIRMATION_STALE'});
   }
   return {};
  }
  if(path.startsWith('/api/recurring-rules'))return page([rule]);
  if(path.startsWith('/api/recurring-occurrences'))return page([{id:3,ruleId:1,dueOn:'2026-01-01',assignedUserId:7,status:'PENDING'}]);
  return path==='/api/members'?[]:page([]);
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><RecurringPage request={request as RequestFn} role="OWNER" userId={7}/></QueryClientProvider>);
 await userEvent.click(await screen.findByRole('button',{name:'确认入账'}));
 await userEvent.click(screen.getByRole('button',{name:'记录本次账单'}));
 await screen.findByText('账单已变更');
 expect(screen.getByRole('button',{name:'记录本次账单'})).toBeDisabled();
 await userEvent.click(screen.getByRole('button',{name:'重新核对账单'}));
 await screen.findByRole('heading',{name:'奖金'});
 await userEvent.click(screen.getByRole('button',{name:'确认入账'}));
 expect(within(screen.getByRole('dialog',{name:'账单详情'})).getByText('新收款账户')).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'记录本次账单'}));
 expect(request).toHaveBeenCalledWith('/api/recurring-occurrences/3/confirm',{method:'POST',body:{amount:'12.00',confirmationToken:'review-v2'}});
});

it('keeps all expense categories in the denominator and groups the remaining categories', async () => {
  const rules = Array.from({ length: 7 }, (_, index) => ({
    id: index + 1, categoryId: index + 1, categoryName: `分类${index + 1}`,
    kind: 'expense', amount: index === 0 ? '40.00' : '10.00',
    active: true, paused: false, scheduleType: 'MONTHLY', intervalValue: 1,
  }));
  const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1, hasNext: false });
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/recurring-rules')) return page(rules);
    return path === '/api/members' ? [] : page([]);
  });
  render(<QueryClientProvider client={new QueryClient()}><RecurringPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);

  const chart = await screen.findByRole('img', { name: '支出分类：分类1 40.0%，分类2 10.0%，分类3 10.0%，分类4 10.0%，分类5 10.0%，其他 20.0%' });
  expect(chart).toBeInTheDocument();
});

it('preselects sole eligible options and shows rules after saving while allowing month-end dates', async () => {
  const request = vi.fn(async (path: string, options?: { method?: string }) => {
    if (path === '/api/recurring-rules' && options?.method === 'POST') return {id: 9};
    if (path === '/api/recurring-rules?includeInactive=true&page=0&size=10') return { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/recurring-rules?includeInactive=true&page=0&size=50') return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/recurring-occurrences?status=PENDING&page=0&size=10') return { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0, hasNext: false };
    if (path.startsWith('/api/accounts')) return { items: [{ id: 1, name: '日常账户', type: 'BANK', currency: 'CNY', openingBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', availableBalance: '0.00', balance: '0.00', archivedAt: null }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.startsWith('/api/categories')) return { items: [{ id: 2, kind: 'expense', name: '餐饮', color: '#3370FF', defaultCategory: false, createdAt: '', parentId: null, level: 1, children: [] }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path === '/api/members') return [{ id: 3, name: 'Kevin', roleLabel: '本人', createdAt: '' }];
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 4, userId: 7, email: 'demo@example.com', displayName: '演示用户', role: 'OWNER', status: 'ACTIVE' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    throw new Error(`unexpected ${path}`);
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(<QueryClientProvider client={client}><RecurringPage request={request as RequestFn} role="OWNER" userId={7} /></QueryClientProvider>);
  await user.click(await screen.findByRole('button', { name: '新建周期规则' }));
  expect(screen.getByLabelText('每月日期')).toHaveAttribute('max', '31');
  expect(screen.getByLabelText('账户')).toHaveValue('1');
  expect(screen.getByLabelText('归属成员')).toHaveValue('3');
  expect(screen.getByLabelText('确认人')).toHaveValue('7');
  await user.type(screen.getByLabelText('金额'), '10');
  await user.selectOptions(screen.getByLabelText('分类'), '2');
  await user.click(screen.getByRole('button', {name: '保存周期规则'}));
  expect(await screen.findByRole('heading', {name: '周期规则'})).toBeInTheDocument();
});
