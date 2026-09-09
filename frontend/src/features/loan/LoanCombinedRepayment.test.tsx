import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoanPrepaymentPanel } from './LoanPrepaymentPanel';
import { LoansPage } from './LoansPage';
import { ApiError, createApiClient } from '../../api/client';
import { useState } from 'react';
import type { Account, Loan } from '../../api/contracts';
import type { RequestFn } from '../common';

const loan = { id: 4, name: '家庭贷款', currentPrincipal: '10000.00', principal: '10000.00', paymentAccountId: 1, accountingInitialized: true, accountingOn: '2026-01-01', repaymentMethod: 'EQUAL_PAYMENT', status: 'ACTIVE', annualRate: '0.12', termMonths: 10, startOn: '2026-01-01' } as Loan;
const accounts = [{ id: 1, name: '还款账户', openingConfirmed: true, availableBalance: '5000.00' }, { id: 2, name: '备用账户', openingConfirmed: true, availableBalance: '6000.00' }] as Account[];
const policy = { minimumInstallmentAmount: null, sourceNote: null, revision: 0 };
const termOptions = [1, 2, 3].map(periods => ({ periods, allowed: true, reason: null, firstPaymentAmount: '2000.00', roundingPolicy: 'FIXED_CASH_V1' }));
function quote(path: string, token = 'quote-token') {
 const p = new URLSearchParams(path.split('?')[1]);
 const closed = p.get('additionalPrincipal') === '9000.00';
 const summary = (after: boolean) => ({ principalAmount: after ? closed ? '0.00' : '6000.00' : '9000.00', periodCount: after && closed ? 0 : 3, maturityOn: closed && after ? null : '2027-03-01', nextPaymentOn: closed && after ? null : '2027-01-01', nextPaymentAmount: closed && after ? null : '2100.00', totalInterest: closed && after ? '0.00' : '300.00', repaymentTotal: after ? closed ? '0.00' : '6300.00' : '9300.00', schedule: [] });
 return { dueInstallments: [{ installmentId: 10, installmentNo: 1, dueOn: '2026-01-31', principalAmount: '1000.00', interestAmount: '100.00', cashAmount: '1100.00' }], duePrincipalAmount: '1000.00', dueInterestAmount: '100.00', additionalPrincipal: closed ? '9000.00' : '3000.00', totalPrincipalAmount: closed ? '10000.00' : '4000.00', totalInterestAmount: '100.00', totalCashAmount: closed ? '10100.00' : '4100.00', paymentAccountId: Number(p.get('paymentAccountId') ?? 1), availableBalance: '5000.00', balanceAfter: closed ? '-5100.00' : '900.00', paidOn: p.get('paidOn') ?? '2026-01-31', strategy: p.get('strategy') ?? 'REDUCE_PAYMENT', targetPeriods: p.has('targetPeriods') ? Number(p.get('targetPeriods')) : null, before: summary(false), after: summary(true), policy, termOptions: closed ? [] : termOptions, planToken: token };
}
function metadata(path: string) {
 return path.endsWith('/repayment-policy') ? policy : path.includes('/term-options?') ? { remainingPrincipal: '9000.00', duePrincipal: '1000.00', dueInterest: '100.00', policy, options: termOptions } : undefined;
}
function mount(request: RequestFn, onPaid = vi.fn(async () => undefined)) {
 const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
 render(<QueryClientProvider client={cache}><LoanPrepaymentPanel loan={loan} accounts={accounts} request={request} onClose={vi.fn()} onPaid={onPaid} onPayoff={vi.fn()} /></QueryClientProvider>);
 return { user: userEvent.setup(), onPaid, cache };
}
const enter = (amount = '3000.00') => fireEvent.change(screen.getByLabelText('额外提前偿还本金'), { target: { value: amount } });

it('discloses due plus extra as 4100, server balances 5000 to 900, and sends exactly one normalized combined request', async () => {
 const writes: Array<{ path: string; body: unknown }> = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes.push({ path, body: options.body }); return { ...quote(''), batchId: 8 } as T; }
  return (metadata(path) ?? quote(path)) as T;
 };
 const { user, onPaid } = mount(request); enter('3000');
 const confirm = await screen.findByRole('button', { name: '确认还款 ¥4,100.00' });
 const bill = screen.getByRole('region', { name: '本次还款明细' });
 expect(bill).toHaveTextContent('到期本金¥1,000.00'); expect(bill).toHaveTextContent('到期利息¥100.00');
 expect(bill).toHaveTextContent('额外提前偿还本金¥3,000.00'); expect(bill).toHaveTextContent('本次总付款¥4,100.00');
 expect(bill).toHaveTextContent('付款前账内余额¥5,000.00'); expect(bill).toHaveTextContent('预计付款后余额¥900.00');
 await user.click(confirm); await waitFor(() => expect(onPaid).toHaveBeenCalledOnce());
 expect(writes).toEqual([{ path: '/api/loans/4/repayment', body: { additionalPrincipal: '3000.00', paidOn: expect.any(String), paymentAccountId: 1, strategy: 'REDUCE_PAYMENT', targetPeriods: null, planToken: 'quote-token', idempotencyKey: expect.any(String) } }]);
});

it.each([new TypeError('响应丢失'), new ApiError('服务暂不可用', { status: 503 }), new ApiError('登录需要恢复', { status: 401 })])('replays the full frozen body after an ambiguous error even when a fresh preview fails: %s', async failure => {
 let committed = false; const writes: unknown[] = []; let reads = 0;
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes.push(structuredClone(options.body)); if (!committed) { committed = true; throw failure; } return { ...quote(''), batchId: 8 } as T; }
  if (path.includes('/repayment-preview?')) { reads++; if (committed) throw new ApiError('贷款已结清', { status: 409, code: 'LOAN_CLOSED' }); return quote(path, 'frozen-token') as T; }
  return metadata(path) as T;
 };
 const { user, onPaid } = mount(request); enter(); await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' }));
 await screen.findByText(failure.message); const before = reads;
 await user.click(screen.getByRole('button', { name: '重新预览还款计划' })); await waitFor(() => expect(reads).toBeGreaterThan(before));
 expect(screen.getByLabelText('额外提前偿还本金')).toBeDisabled();
 expect(screen.getByRole('region', { name: '本次还款明细' })).toHaveTextContent('¥4,100.00');
 await user.click(screen.getByRole('button', { name: '核对本次还款结果' })); await waitFor(() => expect(onPaid).toHaveBeenCalledOnce());
 expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]); expect(writes[0]).toMatchObject({ planToken: 'frozen-token', additionalPrincipal: '3000.00', targetPeriods: null });
});

it.each(['INSUFFICIENT_FUNDS', 'LOAN_PLAN_CHANGED', 'LOAN_PLAN_SEARCH_LIMIT'])('keeps draft editable and requires a refreshed quote after definite %s rejection', async code => {
 const writes: Record<string, unknown>[] = []; let reads = 0;
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes.push(options.body as Record<string, unknown>); if (writes.length === 1) throw new ApiError('重新核对资金和计划', { status: 409, code }); return {} as T; }
  return (path.includes('/repayment-preview?') ? quote(path, `token-${++reads}`) : metadata(path)) as T;
 };
 const { user } = mount(request); enter(); await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' }));
 await screen.findByText('重新核对资金和计划'); await waitFor(() => expect(reads).toBe(2));
 expect(screen.getByLabelText('额外提前偿还本金')).toBeEnabled(); expect(screen.getByLabelText('额外提前偿还本金')).toHaveValue('3000.00'); expect(writes).toHaveLength(1);
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' })); await waitFor(() => expect(writes).toHaveLength(2));
 expect(writes[1]).toMatchObject({ planToken: 'token-2' });
});

it('suppresses late account quotes and never confirms without a matching amount/date/strategy/term quote', async () => {
 const pending: Array<{ path: string; resolve: (data: unknown) => void }> = []; const writes: Record<string, unknown>[] = [];
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes.push(options.body as Record<string, unknown>); return {} as T; }
  if (path.includes('/repayment-preview?')) return new Promise(resolve => pending.push({ path, resolve: resolve as (data: unknown) => void }));
  return metadata(path) as T;
 };
 const { user } = mount(request); enter(); await waitFor(() => expect(pending).toHaveLength(1));
 await user.selectOptions(screen.getByLabelText('本次付款账户'), '2'); await waitFor(() => expect(pending).toHaveLength(2));
 await act(async () => pending[0].resolve(quote(pending[0].path, 'old-account'))); expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled();
 await act(async () => pending[1].resolve(quote(pending[1].path, 'account-2')));
 for (const change of [() => enter('3000'), () => fireEvent.change(screen.getByLabelText('实际还款日期'), { target: { value: '2026-01-31' } }), () => user.click(screen.getByRole('radio', { name: /自选更短期数/ }))]) {
  const n = pending.length; await change();
  if (n === 4) { expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled(); await user.selectOptions(screen.getByLabelText('后续还款期数'), '2'); }
  await waitFor(() => expect(pending).toHaveLength(n + 1)); expect(screen.getByRole('button', { name: '确认还款' })).toBeDisabled();
  await act(async () => pending[n].resolve(quote(pending[n].path, `fresh-${n}`)));
 }
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' })); await waitFor(() => expect(writes).toHaveLength(1));
 expect(writes[0]).toMatchObject({ paymentAccountId: 2, strategy: 'ADJUST_TERM', targetPeriods: 2, paidOn: '2026-01-31', planToken: 'fresh-4' });
});

it('uses zero-extra metadata to offer full closure in the same combined form', async () => {
 const request: RequestFn = async <T,>(path: string) => (metadata(path) ?? quote(path)) as T;
 const { user } = mount(request);
 await user.click(await screen.findByRole('button', { name: '填入全部剩余本金 ¥9,000.00' }));
 expect(screen.getByLabelText('额外提前偿还本金')).toHaveValue('9000.00');
 expect(await screen.findByText('本次还款后将结清贷款本金')).toBeInTheDocument();
 expect(screen.getByRole('button', { name: '确认还款 ¥10,100.00' })).toBeEnabled();
});

it('keeps the confirmed breakdown from one quote even when the independent zero-extra projection has changed', async () => {
 const request: RequestFn = async <T,>(path: string) => {
  if (path.includes('/term-options?') && new URLSearchParams(path.split('?')[1]).get('additionalPrincipal') === '0') return { remainingPrincipal: '8000.00', duePrincipal: '2000.00', dueInterest: '999.00', policy, options: termOptions } as T;
  return (metadata(path) ?? quote(path)) as T;
 };
 mount(request); enter(); await screen.findByRole('button', { name: '确认还款 ¥4,100.00' });
 expect(screen.queryByText(/¥999.00/)).not.toBeInTheDocument();
 expect(screen.getByRole('button', { name: '填入全部剩余本金 ¥9,000.00' })).toBeEnabled();
});

it('offers an explicit in-form closure preview when full extra makes an unselected adjusted term unnecessary', async () => {
 const request: RequestFn = async <T,>(path: string) => (metadata(path) ?? quote(path)) as T;
 const { user } = mount(request); await user.click(screen.getByRole('radio', { name: /自选更短期数/ })); enter('9000.00');
 await user.click(await screen.findByRole('button', { name: '预览结清（不再设置后续期数）' }));
 expect(await screen.findByText('本次还款后将结清贷款本金')).toBeInTheDocument();
 expect(screen.getByRole('button', { name: '确认还款 ¥10,100.00' })).toBeEnabled();
});

it('groups immutable repayment batch children once and labels original balances separately from current loan state', async () => {
 const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalPages: 1, totalElements: items.length, hasNext: false });
 const batch = { ...quote(''), batchId: 8, loanId: 4, status: 'ACTIVE', remainingPrincipal: '6000.00', recordedAt: '2026-01-31T10:00:00', children: [{ sourceType: 'LOAN_PAYMENT', sourceId: 10, transactionId: 20, principalAmount: '1000.00', interestAmount: '100.00', cashAmount: '1100.00' }, { sourceType: 'LOAN_PREPAYMENT', sourceId: 11, transactionId: 21, principalAmount: '3000.00', interestAmount: '0.00', cashAmount: '3000.00' }] };
 const request: RequestFn = async <T,>(path: string) => (path === '/api/loans/4' ? { ...loan, currentPrincipal: '2000.00' } : path.startsWith('/api/loans?') ? page([loan]) : path.endsWith('/repayments') ? [batch] : path.endsWith('/prepayments') ? [{ id: 11, repaymentBatchId: 8, paidOn: '2026-01-31', principalAmount: '3000.00', interestAmount: '0.00', cashAmount: '3000.00', operationKind: 'PREPAYMENT' }, { id: 7, repaymentBatchId: null, paidOn: '2026-01-01', principalAmount: '100.00', interestAmount: '0.00', cashAmount: '100.00', operationKind: 'PREPAYMENT' }] : path === '/api/members' ? [] : page([])) as T;
 render(<QueryClientProvider client={new QueryClient()}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);
 await userEvent.click(await screen.findByRole('button', { name: '查看计划' }));
 const history = await screen.findByRole('region', { name: '提前还款与结清历史' });
 expect(within(history).getAllByRole('article')).toHaveLength(2);
 expect(history).toHaveTextContent('当时付款后余额 ¥900.00'); expect(history).toHaveTextContent('当时剩余本金 ¥6,000.00');
 expect(within(history).getAllByText(/交易 #/)).toHaveLength(2); expect(history).toHaveTextContent('¥100.00');
});

it('labels sale-funded repayment history without inventing another bank withdrawal',async()=>{
 const page=(items:unknown[])=>({items,page:0,size:50,totalPages:1,totalElements:items.length,hasNext:false});
 const batch={...quote(''),batchId:8,loanId:4,remainingPrincipal:'6000.00',recordedAt:'2026-01-31',cashImpact:false,settlementAssetId:7,children:[]};
 const request:RequestFn=async<T,>(path:string)=>(path==='/api/loans/4'?loan:path.startsWith('/api/loans?')?page([loan]):path.endsWith('/repayments')?[batch]:path.endsWith('/prepayments')?[{id:9,repaymentBatchId:null,paidOn:'2026-01-01',principalAmount:'100.00',interestAmount:'0.00',cashAmount:'100.00',operationKind:'PAYOFF',cashImpact:false,settlementAssetId:7}]:path==='/api/members'?[]:page([])) as T;
 render(<QueryClientProvider client={new QueryClient()}><LoansPage request={request} role="OWNER"/></QueryClientProvider>);
 await userEvent.click(await screen.findByRole('button',{name:'查看计划'}));
 const history=await screen.findByRole('region',{name:'提前还款与结清历史'});
 expect(history).toHaveTextContent('出售款代偿');
 expect(history).toHaveTextContent('买方代偿，无单独账户扣款');
 expect(history).toHaveTextContent('买方代偿 ¥100.00');
 expect(history).not.toHaveTextContent('当时付款后余额');
 expect(history).not.toHaveTextContent('本次总付款');
});

it('refreshes and opens the current plan after one combined write', async () => {
 let paid = false; let writes = 0; const scheduleReads: boolean[] = [];
 const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalPages: 1, totalElements: items.length, hasNext: false });
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') { writes++; paid = true; return { ...quote(''), batchId: 8 } as T; }
  if (path.includes('/schedule?')) { scheduleReads.push(paid); return page([{ id: paid ? 25 : 10, installmentNo: paid ? 25 : 10, dueOn: '3999-01-01', principal: paid ? '2000.00' : '3000.00', interest: '100.00', status: 'PENDING', confirmedTransactionId: null }]) as T; }
  return (metadata(path) ?? (path.includes('/repayment-preview?') ? quote(path) : path === '/api/loans/4' ? { ...loan, currentPrincipal: paid ? '6000.00' : '10000.00' } : path.startsWith('/api/loans?') ? page([loan]) : path.startsWith('/api/accounts?') ? page(accounts) : path === '/api/members' || path.endsWith('/prepayments') || path.endsWith('/repayments') ? [] : page([]))) as T;
 };
 const user = userEvent.setup(); render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);
 await user.click(await screen.findByRole('button', { name: '提前还款' })); enter();
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' }));
 const current = await screen.findByRole('dialog', { name: '家庭贷款 · 还款计划' });
 expect(await within(current).findByText('25')).toBeInTheDocument(); expect(within(current).getByLabelText('计划范围')).toHaveValue('CURRENT');
 expect(current).toHaveTextContent('当前剩余本金 ¥6,000.00'); expect(scheduleReads).toContain(false); expect(scheduleReads).toContain(true); expect(writes).toBe(1);
});

it('opens paid history when CLOSED detail has already refreshed before the combined onPaid callback', async () => {
 let paid = false; let completePost: (value: unknown) => void = () => {}; const scheduleReads: string[] = [];
 const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
 const closed = { ...loan, status: 'CLOSED', currentPrincipal: '0.00' };
 const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalPages: items.length ? 1 : 0, totalElements: items.length, hasNext: false });
 const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
  if (options?.method === 'POST') {
   paid = true;
   // AuthProvider's awaited write refresh can publish CLOSED before the mutation succeeds.
   cache.setQueryData(['loans', 'detail', 4], closed);
   return new Promise(resolve => { completePost = resolve as (value: unknown) => void; });
  }
  if (path.includes('/schedule?')) {
   scheduleReads.push(path); const history = new URLSearchParams(path.split('?')[1]).get('view') === 'HISTORY';
   return page(paid && history ? [{ id: 10, installmentNo: 1, dueOn: '2026-01-31', principal: '1000.00', interest: '100.00', cashAmount: '1100.00', status: 'PAID', paidOn: '2026-01-31', confirmedTransactionId: 20 }] : []) as T;
  }
  return (metadata(path) ?? (path.includes('/repayment-preview?') ? { ...quote(path), availableBalance: '20000.00', balanceAfter: '9900.00' } : path === '/api/loans/4' ? paid ? closed : loan : path.startsWith('/api/loans?') ? page([paid ? closed : loan]) : path.startsWith('/api/accounts?') ? page(accounts) : path === '/api/members' || path.endsWith('/prepayments') || path.endsWith('/repayments') ? [] : page([]))) as T;
 };
 const user = userEvent.setup(); render(<QueryClientProvider client={cache}><LoansPage request={request} role="OWNER" /></QueryClientProvider>);
 await user.click(await screen.findByRole('button', { name: '提前还款' })); enter('9000.00');
 await user.click(await screen.findByRole('button', { name: '确认还款 ¥10,100.00' }));
 await waitFor(() => expect(scheduleReads.some(path => path.includes('view=HISTORY'))).toBe(true));
 expect(screen.getByRole('dialog', { name: '家庭贷款 · 提前还款' })).toHaveTextContent('当前剩余本金 ¥0.00');
 await act(async () => { completePost({ batchId: 8, status: 'CLOSED', remainingPrincipal: '0.00' }); });
 const history = await screen.findByRole('dialog', { name: '家庭贷款 · 还款计划' });
 expect(within(history).getByLabelText('计划范围')).toHaveValue('HISTORY');
 expect(await within(history).findByText('已记录还款')).toBeInTheDocument();
 expect(scheduleReads.at(-1)).toContain('view=HISTORY'); expect(scheduleReads.at(-1)).toContain('page=0');
});

it.each([[401, false], [403, false], [401, true]] as const)('keeps real client recovery through status %s and honors explicit session reset=%s', async (failureStatus, resetSession) => {
 let expired = false; let recovered = false; let endSession = () => {}; let csrfReads = 0; const bodies: string[] = []; const headers: string[] = [];
 let completeSlow: (response: Response) => void = () => {};
 const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
 const client = createApiClient({ onSessionExpired: () => endSession(), fetchImpl: async (input, init) => {
  const path = String(input);
  if (path === '/api/unrelated-parent') return respond(401, { error: { code: 'AUTH_REQUIRED', message: '后台读取登录过期' } });
  if (path === '/api/slow-parent') return new Promise(resolve => { completeSlow = resolve; });
  if (path === '/api/csrf') { csrfReads++; return respond(200, { data: { headerName: 'X-CSRF', token: recovered ? 'renewed-csrf' : 'initial-csrf' } }); }
  if (init?.method === 'POST') {
   bodies.push(String(init.body)); headers.push(new Headers(init.headers).get('X-CSRF')!);
   if (!expired) { expired = true; return respond(failureStatus, { error: { code: failureStatus === 403 ? 'FORBIDDEN' : 'AUTH_REQUIRED', message: '请先恢复登录' } }); }
   if (headers.at(-1) !== 'renewed-csrf') return respond(403, { error: { code: 'CSRF_INVALID', message: '安全凭证过期' } });
   return respond(200, { data: { ...quote(''), batchId: 8 } });
  }
  if (expired && !recovered) return respond(failureStatus, { error: { code: failureStatus === 403 ? 'FORBIDDEN' : 'AUTH_REQUIRED', message: '请先恢复登录' } });
  return respond(200, { data: metadata(path) ?? quote(path) });
 } });
 const onPaid = vi.fn(async () => undefined);
 function SessionBoundary() {
  const [ended, setEnded] = useState(false); endSession = () => setEnded(true);
  return ended ? <p>登录页：原表单已卸载</p> : <LoanPrepaymentPanel loan={loan} accounts={accounts} request={client.api} onClose={vi.fn()} onPaid={onPaid} onPayoff={vi.fn()} />;
 }
 render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><SessionBoundary /></QueryClientProvider>);
 const user = userEvent.setup(); enter(); await user.click(await screen.findByRole('button', { name: '确认还款 ¥4,100.00' }));
 expect(await screen.findByRole('button', { name: '核对本次还款结果' })).toBeEnabled();
 await act(async () => { await expect(client.api('/api/unrelated-parent')).rejects.toMatchObject({ status: 401 }); });
 expect(screen.getByRole('button', { name: '核对本次还款结果' })).toBeEnabled();
 if (resetSession) {
  const pending = client.api('/api/slow-parent'); client.resetSessionScope(); completeSlow(respond(200, { data: { stale: true } }));
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  await act(async () => { await expect(client.api('/api/unrelated-parent')).rejects.toMatchObject({ status: 401 }); });
  expect(screen.getByText('登录页：原表单已卸载')).toBeInTheDocument(); expect(bodies).toHaveLength(1); return;
 }
 await user.click(screen.getByRole('button', { name: '重新预览还款计划' })); await screen.findByText('请先恢复登录');
 expect(screen.getByRole('button', { name: '核对本次还款结果' })).toBeEnabled(); expect(screen.queryByText('登录页：原表单已卸载')).not.toBeInTheDocument();
 recovered = true; await user.click(screen.getByRole('button', { name: '核对本次还款结果' })); await waitFor(() => expect(onPaid).toHaveBeenCalledOnce());
 expect(bodies).toHaveLength(2); expect(bodies[1]).toBe(bodies[0]); expect(headers).toEqual(['initial-csrf', 'renewed-csrf']); expect(csrfReads).toBe(2);
 await act(async () => { await expect(client.api('/api/unrelated-parent')).rejects.toMatchObject({ status: 401 }); });
 expect(screen.getByText('登录页：原表单已卸载')).toBeInTheDocument();
});
