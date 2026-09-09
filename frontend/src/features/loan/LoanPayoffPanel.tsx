import {BankAccountPicker} from '../ledger/BankAccountPicker';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, Loan, LoanPayoffQuote, LoanPrepayment } from '../../api/contracts';
import { ApiError } from '../../api/client';
import { businessDate, newIdempotencyKey } from '../../shared/runtime';
import { DateField } from '../../shared/DateField';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { Drawer, FormError, money, type RequestFn } from '../common';

type PayoffBody = { paidOn: string; paymentAccountId: number; interestAmount: string | null; idempotencyKey: string; planToken: string };
type PayoffAttempt = { body: PayoffBody; quote: LoanPayoffQuote };
const rejectedBeforePosting = new Set(['LOAN_PLAN_CHANGED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_NOT_INITIALIZED', 'ACCOUNTING_BALANCE_MISMATCH', 'LOAN_CLOSED', 'VALIDATION_ERROR', 'ACCOUNT_ACTIVITY_BEFORE_OPENING', 'LOAN_PAYMENT_BEFORE_OPENING', 'LOAN_PAYMENT_CHRONOLOGY', 'STALE_REFERENCE']);

/** One explicit bank-payment confirmation, with a quote tied to the visible inputs. */
export function LoanPayoffPanel({ loan, accounts, accountsReady=true, request, onClose, onPaid }: { loan: Loan; accounts: Account[]; accountsReady?:boolean; request: RequestFn; onClose: () => void; onPaid: () => Promise<void> }) {
 const [draft, setDraft] = useState(() => ({ paidOn: businessDate(), paymentAccountId: String(loan.paymentAccountId), interestAmount: '', idempotencyKey: newIdempotencyKey() }));
 const [sessionKey] = useState(newIdempotencyKey);
 const [attempt, setAttempt] = useState<PayoffAttempt | null>(null);
 const cache = useQueryClient(); const fundsError = useFundsRefresh();
 const params = new URLSearchParams({ paidOn: draft.paidOn, paymentAccountId: draft.paymentAccountId });
 if (draft.interestAmount.trim()) params.set('interestAmount', draft.interestAmount.trim());
 const quote = useQuery({ queryKey: ['loan-payoff-quote', loan.id, sessionKey, params.toString()], queryFn: () => request<LoanPayoffQuote>(`/api/loans/${loan.id}/payoff-quote?${params}`), enabled: Boolean(draft.paidOn && draft.paymentAccountId), retry: false, refetchOnWindowFocus: false });
 const submit = useMutation({ mutationFn: (body: PayoffBody) => request<LoanPrepayment>(`/api/loans/${loan.id}/payoff`, { method: 'POST', body }), onError: error => {
  fundsError(error);
  // These domain checks run after receipt lookup and roll back any new posting.
  // Connectivity/server/auth failures cannot disprove an earlier commit; keep its body.
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && rejectedBeforePosting.has(error.code ?? '')) setAttempt(null);
  if (error instanceof ApiError && ['LOAN_PLAN_CHANGED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_NOT_INITIALIZED'].includes(error.code ?? '')) void quote.refetch();
 }, onSuccess: async () => {
  await Promise.all(['loans', 'loan-schedule', 'loan-prepayments', 'accounts', 'transactions'].map(key => cache.invalidateQueries({ queryKey: [key] })));
  await onPaid();
 } });
 const confirm = () => {
  if (attempt) { submit.mutate(attempt.body); return; }
  if (!quote.data || quote.isFetching || quote.isError) return;
  const next = { body: { ...draft, paymentAccountId: Number(draft.paymentAccountId), interestAmount: draft.interestAmount.trim() || null, planToken: quote.data.planToken }, quote: quote.data };
  setAttempt(next); submit.mutate(next.body);
 };
 const update = (field: 'paidOn' | 'paymentAccountId' | 'interestAmount', value: string) => { submit.reset(); setDraft(old => ({ ...old, [field]: value, idempotencyKey: newIdempotencyKey() })); };
 const selected = accounts.find(a => String(a.id) === draft.paymentAccountId);
 const displayedQuote = attempt?.quote ?? (!quote.isFetching && !quote.isError ? quote.data : undefined);
 const account = selected && displayedQuote ? { ...selected, availableBalance: displayedQuote.availableBalance } : selected;
 return <Drawer open draft={{ ...draft, attemptedRequest: attempt?.body ?? null }} sessionKey={sessionKey} busy={submit.isPending} title={`${loan.name} · 一次结清`} description="按实际付款日期核对剩余本金和利息，一次记录完整结清。" onClose={onClose}>
  <form className="feature-form" onSubmit={event => { event.preventDefault(); confirm(); }}>
   <FormError error={submit.error ?? quote.error} />
   <fieldset disabled={submit.isPending || attempt !== null} className="feature-form">
    <label>实际还款日期<DateField required name="paidOn" min={loan.lastPaymentOn ?? loan.accountingOn ?? undefined} max={businessDate()} value={draft.paidOn} onChange={e => update('paidOn', e.target.value)} /></label>
    <BankAccountPicker accountsReady={accountsReady} label="本次付款账户" name="paymentAccountId" required currency="CNY" request={request} accounts={accounts} value={draft.paymentAccountId} onChange={id => update('paymentAccountId', id)}/>
    <label>本次实际利息（选填）<input name="interestAmount" inputMode="decimal" placeholder="留空使用到期未付利息" value={draft.interestAmount} onChange={e => update('interestAmount', e.target.value)} /></label>
   </fieldset>
   {quote.isFetching && <p role="status">正在核对结清金额…</p>}
   {displayedQuote && <><dl className="loan-amount-grid" aria-label="结清金额明细"><div><dt>剩余本金</dt><dd>{money(displayedQuote.principalAmount)}</dd></div><div><dt>到期未付利息</dt><dd>{money(displayedQuote.dueInterestAmount)}</dd></div><div><dt>本次实际利息</dt><dd>{money(displayedQuote.interestAmount)}</dd></div><div><dt>未到期计划利息（本次不收取）</dt><dd>{money(displayedQuote.futureScheduledInterest)}</dd></div></dl><PaymentPreview account={account} amount={displayedQuote.cashAmount} /></>}
   {attempt && !submit.isPending && <p role="status">上次提交的结果尚未确认。请用原付款信息核对本次结清结果；重复核对不会重复记账。</p>}
   <p className="source-note">计划剩余本息 {money(loan.remainingRepaymentTotal)} 包含未到期利息。结清按上方金额扣减本次账户，保留已还历史并取消剩余计划；未来默认付款账户不变。手续费请按真实发生另记费用。</p>
   <div className="form-footer"><Button disabled={submit.isPending || quote.isFetching} onClick={() => { submit.reset(); void quote.refetch(); }}>重新核对结清金额</Button><Button htmlType="submit" theme="solid" loading={submit.isPending} disabled={submit.isPending || (!attempt && (!quote.data || quote.isFetching || quote.isError))}>{attempt ? '核对本次结清结果' : '确认一次结清'}</Button></div>
  </form>
 </Drawer>;
}
