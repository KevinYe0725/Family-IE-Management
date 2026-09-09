import {BankAccountPicker} from '../ledger/BankAccountPicker';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, Loan, LoanRepayment, LoanRepaymentPreview, LoanRepaymentRequest, LoanTermOptions, PrepaymentStrategy } from '../../api/contracts';
import { ApiError } from '../../api/client';
import { businessDate, newIdempotencyKey } from '../../shared/runtime';
import { DateField } from '../../shared/DateField';
import { AccountOptions, cents, useFundsRefresh } from '../accounting';
import { Drawer, FormError, dateText, money, type RequestFn } from '../common';
import { LoanStrategyComparison } from './LoanStrategyComparison';
import { LoanRepaymentPolicyPanel } from './LoanRepaymentPolicyPanel';

type Attempt = { body: LoanRepaymentRequest; preview: LoanRepaymentPreview };
const rejectedBeforePosting = new Set(['LOAN_PLAN_CHANGED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_NOT_INITIALIZED', 'ACCOUNTING_BALANCE_MISMATCH', 'LOAN_CLOSED', 'VALIDATION_ERROR', 'ACCOUNT_ACTIVITY_BEFORE_OPENING', 'LOAN_PAYMENT_BEFORE_OPENING', 'LOAN_PAYMENT_CHRONOLOGY', 'STALE_REFERENCE', 'LOAN_FIXED_TERM_INFEASIBLE', 'LOAN_PLAN_INVALID', 'LOAN_PAYOFF_REQUIRED', 'LOAN_CONTRACT_MINIMUM', 'INSTALLMENT_UNASSIGNED', 'LOAN_POLICY_CHANGED', 'LOAN_PLAN_SEARCH_LIMIT']);
const optionReason = (reason: string | null) => reason === 'LOAN_PLAN_SEARCH_LIMIT' ? '计算尚未确定，请调整输入后重试' : reason === 'BELOW_CONTRACT_MINIMUM' || reason === 'LOAN_CONTRACT_MINIMUM' ? '低于合同最低常规还款额' : '无法形成有效的分币还款计划';

export function LoanPrepaymentPanel({ loan, accounts, accountsReady=true, request, onClose, onPaid, onPayoff }: { loan: Loan; accounts: Account[]; accountsReady?:boolean; request: RequestFn; onClose: () => void; onPaid: () => Promise<void>; onPayoff: () => void }) {
 const [draft, setDraft] = useState(() => ({ additionalPrincipal: '', paidOn: businessDate(), paymentAccountId: String(loan.paymentAccountId), strategy: 'REDUCE_PAYMENT' as PrepaymentStrategy, targetPeriods: '', idempotencyKey: newIdempotencyKey() }));
 const [sessionKey] = useState(newIdempotencyKey);
 const [attempt, setAttempt] = useState<Attempt | null>(null);
 const recovery = useRef<(() => void) | null>(null);
 const releaseRecovery = () => { recovery.current?.(); recovery.current = null; };
 useEffect(() => () => { recovery.current?.(); }, []);
 const [policyDraft, setPolicyDraft] = useState<unknown>(null);
 const [policyBusy, setPolicyBusy] = useState(false);
 const [quoteVersion, setQuoteVersion] = useState(0);
 const cache = useQueryClient(); const fundsError = useFundsRefresh();
 const inputCents = cents(draft.additionalPrincipal);
 const validExtra = inputCents !== null && inputCents > 0n;
 const contextEnabled = Boolean(draft.paidOn && draft.paymentAccountId && !policyDraft);
 const params = new URLSearchParams({ additionalPrincipal: draft.additionalPrincipal, paidOn: draft.paidOn, paymentAccountId: draft.paymentAccountId, strategy: draft.strategy });
 if (draft.strategy === 'ADJUST_TERM' && draft.targetPeriods) params.set('targetPeriods', draft.targetPeriods);
 const enabled = contextEnabled && validExtra && (draft.strategy !== 'ADJUST_TERM' || Boolean(draft.targetPeriods));
 const preview = useQuery({ queryKey: ['loan-repayment-preview', loan.id, sessionKey, quoteVersion, params.toString()], queryFn: () => request<LoanRepaymentPreview>(`/api/loans/${loan.id}/repayment-preview?${params}`, { handleUnauthorized: false }), enabled, retry: false, refetchOnWindowFocus: false });
 // Evaluate current extra independently: an infeasible strategy must not hide feasible shorter terms.
 const optionParams = new URLSearchParams({ additionalPrincipal: draft.additionalPrincipal.trim() || '0', paidOn: draft.paidOn, paymentAccountId: draft.paymentAccountId });
 const terms = useQuery({ queryKey: ['loan-term-options', loan.id, sessionKey, quoteVersion, optionParams.toString()], queryFn: () => request<LoanTermOptions>(`/api/loans/${loan.id}/term-options?${optionParams}`, { handleUnauthorized: false }), enabled: contextEnabled && (validExtra || !draft.additionalPrincipal.trim()), retry: false, refetchOnWindowFocus: false });
 // Zero-extra projection supplies the post-due maximum without browser money arithmetic.
 const maximumParams = new URLSearchParams({ additionalPrincipal: '0', paidOn: draft.paidOn, paymentAccountId: draft.paymentAccountId });
 const maximum = useQuery({ queryKey: ['loan-term-options', loan.id, sessionKey, quoteVersion, maximumParams.toString()], queryFn: () => request<LoanTermOptions>(`/api/loans/${loan.id}/term-options?${maximumParams}`, { handleUnauthorized: false }), enabled: contextEnabled, retry: false, refetchOnWindowFocus: false });
 const refreshQuote = () => setQuoteVersion(version => version + 1);
 const submit = useMutation({ mutationFn: (body: LoanRepaymentRequest) => request<LoanRepayment>(`/api/loans/${loan.id}/repayment`, { method: 'POST', body, handleUnauthorized: false }), onError: error => {
  fundsError(error);
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && rejectedBeforePosting.has(error.code ?? '')) { releaseRecovery(); setAttempt(null); refreshQuote(); }
 }, onSuccess: async () => {
  releaseRecovery();
  await Promise.all(['loans', 'loan-schedule', 'loan-prepayments', 'loan-repayments', 'accounts', 'transactions'].map(key => cache.invalidateQueries({ queryKey: [key] })));
  await onPaid();
 } });
 const confirm = () => {
  if (submit.isPending || policyBusy) return;
  if (attempt) { submit.mutate(attempt.body); return; }
  if (!enabled || !preview.data || preview.isFetching || preview.isError) return;
  const quote = preview.data;
  const next: Attempt = { body: { additionalPrincipal: quote.additionalPrincipal, paidOn: quote.paidOn, paymentAccountId: quote.paymentAccountId, strategy: quote.strategy, targetPeriods: quote.targetPeriods, planToken: quote.planToken, idempotencyKey: draft.idempotencyKey }, preview: quote };
  // Acquire before dispatch, including before React can run an effect: a late
  // parent query's 401 must not discard a command whose outcome is uncertain.
  recovery.current = request.beginRecoverableOperation?.() ?? null;
  setAttempt(next); submit.mutate(next.body);
 };
 const update = (field: 'additionalPrincipal' | 'paidOn' | 'paymentAccountId' | 'strategy' | 'targetPeriods', value: string) => {
  submit.reset(); setDraft(old => ({ ...old, [field]: value, ...(field === 'strategy' ? { targetPeriods: '' } : {}), idempotencyKey: newIdempotencyKey() }));
 };
 const displayed = attempt?.preview ?? (enabled && !preview.isFetching && !preview.isError ? preview.data : undefined);
 const matchingTerms = contextEnabled && !terms.isFetching && !terms.isError ? terms.data : undefined;
 const options = displayed?.termOptions ?? matchingTerms?.options;
 const hasUndetermined = options?.some(option => option.evaluationStatus === 'UNDETERMINED' || option.reason === 'LOAN_PLAN_SEARCH_LIMIT');
 const projected = contextEnabled && !maximum.isFetching && !maximum.isError ? maximum.data : undefined;
 const context = displayed ? { duePrincipal: displayed.duePrincipalAmount, dueInterest: displayed.dueInterestAmount, remainingPrincipal: displayed.before.principalAmount } : projected;
 const futureCount = displayed?.before.periodCount ?? (options?.length ? Math.max(...options.map(option => option.periods)) : 0);
 const error = submit.error ?? (enabled ? preview.error : null) ?? terms.error ?? maximum.error;
 const locked = submit.isPending || attempt !== null || policyBusy;
 const closed = displayed?.after.periodCount === 0;
 return <Drawer open draft={{ ...draft, policyDraft, attemptedRequest: attempt?.body ?? null }} sessionKey={sessionKey} busy={submit.isPending || policyBusy} title={`${loan.name} · 提前还款`} description={`当前剩余本金 ${money(loan.currentPrincipal)}；一次确认已到期款与额外提前偿还本金。`} onClose={() => { releaseRecovery(); onClose(); }}>
  <form className="feature-form loan-prepayment-form" onSubmit={event => { event.preventDefault(); confirm(); }}>
   <FormError error={error} />
   <fieldset className="feature-form" disabled={locked}>
    <label>额外提前偿还本金<input required name="additionalPrincipal" inputMode="decimal" aria-invalid={Boolean(draft.additionalPrincipal.trim() && !validExtra)} aria-describedby={draft.additionalPrincipal.trim() && !validExtra ? 'additional-principal-error' : undefined} value={draft.additionalPrincipal} onChange={event => update('additionalPrincipal', event.target.value)} /></label>
    {draft.additionalPrincipal.trim() && !validExtra && <p id="additional-principal-error" role="alert">额外本金请输入大于 0 且最多两位小数的金额。</p>}
    {context && <div className="source-note"><p>按所选日期，先处理到期本金 {money(context.duePrincipal)} 和到期利息 {money(context.dueInterest)}，再偿还额外本金。</p>{(cents(context.remainingPrincipal) ?? 0n) > 0n && <Button size="small" onClick={() => update('additionalPrincipal', context.remainingPrincipal)}>填入全部剩余本金 {money(context.remainingPrincipal)}</Button>}</div>}
    {draft.strategy === 'ADJUST_TERM' && validExtra && projected && inputCents === cents(projected.remainingPrincipal) && <Button onClick={() => update('strategy', 'REDUCE_PAYMENT')}>预览结清（不再设置后续期数）</Button>}
    <label>实际还款日期<DateField required name="paidOn" min={loan.lastPaymentOn ?? loan.accountingOn ?? undefined} max={businessDate()} value={draft.paidOn} onChange={event => update('paidOn', event.target.value)} /></label>
    <BankAccountPicker accountsReady={accountsReady} label="本次付款账户" name="paymentAccountId" required currency="CNY" request={request} accounts={accounts} value={draft.paymentAccountId} onChange={id => update('paymentAccountId', id)}/>
   </fieldset>
   {enabled && preview.isFetching && <p role="status">正在核对到期款、额外本金与后续计划…</p>}
   {displayed && <section className="loan-repayment-bill" aria-label="本次还款明细">
    <p>截至 {dateText(displayed.paidOn)}，本次包含已到期 {displayed.dueInstallments.length} 期；先还到期款，再还额外本金。</p>
    <dl><div><dt>到期本金</dt><dd>{money(displayed.duePrincipalAmount)}</dd></div><div><dt>到期利息</dt><dd>{money(displayed.dueInterestAmount)}</dd></div><div className="loan-repayment-bill__extra"><dt>额外提前偿还本金</dt><dd>{money(displayed.additionalPrincipal)}</dd></div><div><dt>本金合计</dt><dd>{money(displayed.totalPrincipalAmount)}</dd></div><div><dt>利息合计</dt><dd>{money(displayed.totalInterestAmount)}</dd></div><div className="loan-repayment-bill__total"><dt>本次总付款</dt><dd>{money(displayed.totalCashAmount)}</dd></div><div><dt>付款账户</dt><dd>{accounts.find(account => account.id === displayed.paymentAccountId)?.name ?? `账户 #${displayed.paymentAccountId}`}</dd></div><div><dt>付款前账内余额</dt><dd>{money(displayed.availableBalance)}</dd></div><div><dt>预计付款后余额</dt><dd>{money(displayed.balanceAfter)}</dd></div></dl>
    {(cents(displayed.balanceAfter) ?? 0n) < 0n && <p role="status">账内可用余额不足，请核对资金记录后重新预览；保存时由服务器检查。</p>}
    {closed && <p className="loan-repayment-closure" role="status">本次还款后将结清贷款本金</p>}
    {displayed.dueInstallments.length > 0 && <details><summary>查看本次到期期次</summary>{displayed.dueInstallments.map(row => <p key={row.installmentId}>第 {row.installmentNo} 期 · {dateText(row.dueOn)} · 本金 {money(row.principalAmount)} + 利息 {money(row.interestAmount)} = {money(row.cashAmount)}</p>)}</details>}
   </section>}
   {!closed && <fieldset className="loan-strategy-options" disabled={locked}><legend>后续还款方式</legend>
    <label><input type="radio" name="strategy" value="REDUCE_PAYMENT" checked={draft.strategy === 'REDUCE_PAYMENT'} onChange={() => update('strategy', 'REDUCE_PAYMENT')} /><span><strong>保留期数</strong><small>保留当前未来期数与日期，重新分配付款</small></span></label>
    <label><input type="radio" name="strategy" value="REDUCE_TERM" checked={draft.strategy === 'REDUCE_TERM'} onChange={() => update('strategy', 'REDUCE_TERM')} /><span><strong>按原付款上限缩期</strong><small>保留原逐期付款上限，余额还清后结束</small></span></label>
    <label><input type="radio" name="strategy" value="ADJUST_TERM" checked={draft.strategy === 'ADJUST_TERM'} onChange={() => update('strategy', 'ADJUST_TERM')} /><span><strong>自选更短期数</strong><small>选择更早结束的期数，重新测算各期付款</small></span></label>
    {draft.strategy === 'ADJUST_TERM' && <label className="loan-target-periods">后续还款期数<select required name="targetPeriods" value={draft.targetPeriods} onChange={event => update('targetPeriods', event.target.value)}><option value="">请选择可行期数</option>{options?.filter(option => option.periods < futureCount).map(option => <option key={option.periods} value={option.periods} disabled={!option.allowed}>{option.periods} 期 · {option.allowed ? `首期 ${money(option.firstPaymentAmount)}` : optionReason(option.reason)}</option>)}</select></label>}
    {draft.strategy === 'ADJUST_TERM' && loan.repaymentMethod === 'CUSTOM' && <p className="source-note">自定义本金重分配保留所选前缀日期和合同利息比例，可能提高部分期次付款，请逐期对比。</p>}
    {hasUndetermined && <p role="status">部分期数尚未确定，请调整额外本金或选择已验证的期数后重新预览。未确定不代表没有可行计划。</p>}
    {options && !hasUndetermined && (options.length === 0 || !options.some(option => option.allowed && (draft.strategy !== 'ADJUST_TERM' || option.periods < futureCount))) && <p role="status">没有可用的后续期数。可调整额外本金、核对合同规则，或使用「改为一次结清」。</p>}
   </fieldset>}
   {displayed && <LoanStrategyComparison preview={displayed} method={loan.repaymentMethod} />}
   <LoanRepaymentPolicyPanel loanId={loan.id} sessionKey={sessionKey} request={request} disabled={submit.isPending || attempt !== null} onDraftChange={setPolicyDraft} onChanged={refreshQuote} onBusyChange={setPolicyBusy} />
   {attempt && !submit.isPending && <p role="status">上次提交的结果尚未确认。请用原付款信息核对本次还款结果；重复核对不会重复记账。上方保留首次提交时的金额与余额预览。</p>}
   {error instanceof ApiError && error.status === 401 && <p role="status">请保留本页，在另一标签页使用同一账号恢复登录，再返回核对本次还款结果；刷新或离开本页会丢失当前输入。</p>}
   {attempt && error instanceof ApiError && error.status === 403 && <p role="status">原请求已保留。若刚恢复登录，请再次点击「核对本次还款结果」以刷新安全凭证；若仍无权限，请核对原账号的家庭角色与还款人设置。</p>}
   <p className="source-note">本次到期款与额外本金一起记录，任一环节失败则整笔不记账。所有实际金额按两位小数记账，不会执行银行转账；未来利息为账内测算，合同与费用请向贷款方核对。</p>
   <div className="form-footer"><Button disabled={locked} onClick={onPayoff}>改为一次结清</Button><Button disabled={(!attempt && !enabled) || preview.isFetching || submit.isPending || policyBusy} onClick={() => { submit.reset(); refreshQuote(); }}>重新预览还款计划</Button><Button htmlType="submit" theme="solid" loading={submit.isPending} disabled={submit.isPending || policyBusy || (!attempt && (!enabled || !preview.data || preview.isFetching || preview.isError))}>{attempt ? '核对本次还款结果' : displayed ? `确认还款 ${money(displayed.totalCashAmount)}` : '确认还款'}</Button></div>
  </form>
 </Drawer>;
}
