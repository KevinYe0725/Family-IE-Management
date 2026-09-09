import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, AccountingJournal, CashTransfer, HouseholdRole, Page } from '../../api/contracts';
import { businessDate, newIdempotencyKey } from '../../shared/runtime';
import { DateField } from '../../shared/DateField';
import { PaginationControls, usePageRecovery } from '../../shared/pagination';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { DataPanel, Drawer, FormError, QueryState, isManager, money, type RequestFn } from '../common';

export function TransfersPanel({ request, role, accounts, onHistory }: { request: RequestFn; role: HouseholdRole; accounts: Account[]; onHistory: (id: number) => void }) {
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<{ fromAccountId: string; toAccountId: string; amount: string; occurredOn: string; idempotencyKey: string } | null>(null);
  const history = useQuery({ queryKey: ['transfers', page], queryFn: () => request<Page<CashTransfer>>(`/api/transfers?page=${page}&size=20`, { responseType: 'page' }) });
  const fundsError = useFundsRefresh();
  usePageRecovery(page, history.data, setPage);
  const save = useMutation({ mutationFn: (value: NonNullable<typeof draft>) => request<CashTransfer>('/api/transfers', { method: 'POST', body: { ...value, fromAccountId: Number(value.fromAccountId), toAccountId: Number(value.toAccountId) } }), onError: fundsError, onSuccess: () => { setDraft(null); setPage(0); } });
  return <DataPanel title="账户互转" meta="记录家庭账户间已发生的转账，不计入收入或费用。" action={isManager(role) && <Button onClick={() => setDraft({ fromAccountId: '', toAccountId: '', amount: '', occurredOn: businessDate(), idempotencyKey: newIdempotencyKey() })}>记录账户互转</Button>}>
    <QueryState loading={history.isLoading} error={history.error} empty={!history.data?.items.length && page === 0} emptyTitle="还没有账户互转记录">
      <div className="responsive-data"><table><thead><tr><th>日期</th><th>转出账户</th><th>转入账户</th><th>金额</th><th>历史</th></tr></thead><tbody>{history.data?.items.map(row => <tr key={row.id}><td>{row.occurredOn}</td><td>{accounts.find(a => a.id === row.fromAccountId)?.name ?? `已归档账户 #${row.fromAccountId}`}</td><td>{accounts.find(a => a.id === row.toAccountId)?.name ?? `已归档账户 #${row.toAccountId}`}</td><td>{money(row.amount,accounts.find(a=>a.id===row.fromAccountId)?.currency)}</td><td><button className="text-action" onClick={() => onHistory(row.id)}>账务历史</button></td></tr>)}</tbody></table></div>
      <PaginationControls page={page} totalPages={history.data?.totalPages ?? 0} hasNext={history.data?.hasNext ?? false} onPageChange={setPage} label="账户互转" />
    </QueryState>
    <Drawer open={draft !== null} draft={draft} sessionKey={draft?.idempotencyKey} busy={save.isPending} onSessionStart={save.reset} title="记录账户互转" onClose={() => setDraft(null)}>{draft && <form className="feature-form" onSubmit={e => { e.preventDefault(); save.mutate(draft); }}>
      <FormError error={save.error} />
      <label>转出账户<select required name="fromAccountId" value={draft.fromAccountId} onChange={e => setDraft({ ...draft, fromAccountId: e.target.value })}><option value="">请选择</option><AccountOptions accounts={accounts} /></select></label>
      <label>转入账户<select required name="toAccountId" value={draft.toAccountId} onChange={e => setDraft({ ...draft, toAccountId: e.target.value })}><option value="">请选择</option><AccountOptions accounts={accounts.filter(a => String(a.id) !== draft.fromAccountId && (a.currency??'CNY') === (accounts.find(a=>String(a.id)===draft.fromAccountId)?.currency??'CNY'))} /></select></label>
      <label>互转金额<input required name="amount" inputMode="decimal" value={draft.amount} onChange={e => setDraft({ ...draft, amount: e.target.value })} /></label>
      <label>实际转账日期<DateField required name="occurredOn" max={businessDate()} value={draft.occurredOn} onChange={e => setDraft({ ...draft, occurredOn: e.target.value })} /></label>
      <PaymentPreview account={accounts.find(a => String(a.id) === draft.fromAccountId)} amount={draft.amount} />
      <PaymentPreview incoming account={accounts.find(a => String(a.id) === draft.toAccountId)} amount={draft.amount} />
      <Button htmlType="submit" theme="solid" loading={save.isPending} disabled={!draft.fromAccountId || !draft.toAccountId || draft.fromAccountId === draft.toAccountId}>保存互转记录</Button>
    </form>}</Drawer>
  </DataPanel>;
}

const sources: Record<string, string> = { TRANSACTION: '手工 / 周期收支', CASH_OPENING: '现金期初', CASH_TRANSFER: '账户互转', FX_TRANSFER: '记录换汇', LOAN_OPENING: '贷款期初', LOAN_DISBURSEMENT: '实际放款', LOAN_FINANCED_PURCHASE: '贷款购买物', LOAN_PAYMENT: '计划还款', LOAN_PREPAYMENT: '提前还款', ASSET_ACQUISITION: '资产取得', ASSET_VALUATION: '资产估值', ASSET_DISPOSAL: '资产处置', INVESTMENT_TRADE: '投资交易' };
const accountKinds: Record<string, string> = { CASH: '现金账户', LOAN: '贷款本金', ASSET: '非现金资产', POSITION: '投资持仓成本', EQUITY: '期初权益', EXPENSE: '费用', INCOME: '收入' };
export function AccountingHistory({ request, source,compact=false }: { compact?:boolean;request: RequestFn; source?: { sourceType: string; sourceId: number } }) {
  const [page, setPage] = useState(0);
  const [type, setType] = useState(source?.sourceType ?? '');
  const [sourceId, setSourceId] = useState(source ? String(source.sourceId) : '');
  useEffect(() => { setType(source?.sourceType ?? ''); setSourceId(source ? String(source.sourceId) : ''); setPage(0); }, [source?.sourceType, source?.sourceId]);
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (type) params.set('sourceType', type);
  if (/^[1-9]\d*$/.test(sourceId)) params.set('sourceId', sourceId);
  const history = useQuery({ queryKey: ['accounting-history', params.toString()], queryFn: () => request<Page<AccountingJournal>>(`/api/accounting/history?${params}`, { responseType: 'page' }) });
  usePageRecovery(page, history.data, setPage);
  return <DataPanel title={compact?"入账轨迹":"账务历史"} meta={compact?undefined:"只读入账轨迹。财务更正保留原记录与冲回记录；已经删除的业务仍可追溯。"}>
    {(!compact||!source)&&<div className="filter-bar"><label>业务来源<select value={type} onChange={e => { setType(e.target.value); setSourceId(''); setPage(0); }}><option value="">全部</option>{Object.entries(sources).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>来源编号<input inputMode="numeric" value={sourceId} onChange={e => { setSourceId(e.target.value); setPage(0); }} /></label></div>}
    <QueryState loading={history.isLoading} error={history.error} empty={!history.data?.items.length && page === 0} emptyTitle="没有匹配的入账历史" emptyDetail={compact?undefined:"零期初和不改变价值的事件保留业务记录，不生成零金额账务。"}>
      <div className="audit-list">{history.data?.items.map(row => <details key={row.journalId}><summary tabIndex={compact?0:undefined}><strong>{{ POST: '入账', REPLACE: '更正入账', REVERSE: '冲回原入账' }[row.operation]}</strong><span>{sources[row.sourceType] ?? row.sourceType} #{row.sourceId} · {row.effectiveOn} · 第 {row.revision} 版</span></summary>{compact?<dl className="audit-meta"><div><dt>记录时间</dt><dd>{new Date(row.recordedAt).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai",hour12:false})}</dd></div><div><dt>操作人</dt><dd>#{row.actorId}</dd></div>{row.reversesJournalId&&<div><dt>冲回记录</dt><dd>#{row.reversesJournalId}</dd></div>}</dl>:<p>记录时间 {row.recordedAt} · 操作人 #{row.actorId}{row.reversesJournalId && ` · 冲回记录 #${row.reversesJournalId}`}</p>}<table><thead><tr><th>账务项目</th><th>借方</th><th>贷方</th></tr></thead><tbody>{row.legs.map((leg, index) => <tr key={index}><td title={compact?leg.accountCode:undefined}>{accountKinds[leg.accountCode.split(':')[0]] ?? '账务项目'}{!compact&&<small>{leg.accountCode}</small>}</td><td>{money(leg.debit,leg.currency)}</td><td>{money(leg.credit,leg.currency)}</td></tr>)}</tbody></table></details>)}</div>
      <PaginationControls page={page} totalPages={history.data?.totalPages ?? 0} hasNext={history.data?.hasNext ?? false} onPageChange={setPage} label="账务历史" />
    </QueryState>
  </DataPanel>;
}
