import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Budget, BudgetRevision, BudgetScopeType, BudgetTotal, BudgetTemplate, BudgetUsage, BudgetUsageEntry, Category, HouseholdRole, Member, Page } from '../../api/contracts';
import { useQueryClient } from '@tanstack/react-query';
import { DateField } from '../../shared/DateField';
import { localYearMonth } from '../../shared/runtime';
import { PaginationControls, readAllPages, usePageRecovery } from '../../shared/pagination';
import { CenteredModal, FormError, PageScaffold, QueryState, StatusTag, isManager, money, type RequestFn } from '../common';

function parseCents(raw: string | null | undefined): bigint {
  if (!raw) return 0n;
  const negative = raw.startsWith('-');
  const unsigned = negative ? raw.slice(1) : raw;
  const [whole, fraction = ''] = unsigned.split('.');
  const value = BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
  return negative ? -value : value;
}
function formatYuan(centsValue: bigint): string {
  const negative = centsValue < 0n;
  const absolute = negative ? -centsValue : centsValue;
  return `${negative ? '-' : ''}${(absolute / 100n).toString()}.${(absolute % 100n).toString().padStart(2, '0')}`;
}

// Bright, distinguishable palette assigned to allocation segments in order.
const SEGMENT_PALETTE = ['#3B82F6', '#22C55E', '#F59E0B', '#EF4444', '#06B6D4', '#EC4899', '#F97316', '#8B5CF6', '#84CC16', '#14B8A6'];
const OTHER_COLOR = '#A855F7';
const UNALLOCATED_COLOR = '#CBD5E1';

type BudgetDraft = { id?: number; periodMonth: string; scopeType: BudgetScopeType; categoryId: number | null; memberId: number | null; amount: string; version: number; active: boolean; note: string };
type TotalDraft = { periodMonth: string; amount: string; version: number };

export function BudgetsPage({ request, role }: { request: RequestFn; role: HouseholdRole }) {
  const [filter, setFilter] = useState<'all'|'active'|'inactive'>('all');
  const [month, setMonth] = useState(localYearMonth());
  const [draft, setDraft] = useState<BudgetDraft | null>(null);
  const [totalDraft, setTotalDraft] = useState<TotalDraft | null>(null);
  const [historyId, setHistoryId] = useState<number | null>(null);
  const [entriesId, setEntriesId] = useState<number | null>(null);
  const [entriesPage, setEntriesPage] = useState(0);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [templateName, setTemplateName] = useState('');
  const [templateMessage, setTemplateMessage] = useState<string | null>(null);
  const [usagePage, setUsagePage] = useState(0);
  const [revisionPage, setRevisionPage] = useState(0);
  const manager = isManager(role);
  const usageStatus = filter === 'all' ? '&includeInactive=true' : `&active=${filter === 'active'}&includeInactive=true`;
  const usage = useQuery({ queryKey: ['budget-usage', month, filter, usagePage], queryFn: () => request<Page<BudgetUsage>>(`/api/budgets/usage?periodMonth=${month}&rollupCategories=true${usageStatus}&page=${usagePage}&size=50`, { responseType: 'page' }) });
  const allUsage = useQuery({ queryKey: ['budget-usage', month, 'all-rows'], queryFn: () => readAllPages(page => request<Page<BudgetUsage>>(`/api/budgets/usage?periodMonth=${month}&rollupCategories=true&includeInactive=true&page=${page}&size=50`, { responseType: 'page' })), enabled: manager });
  const total = useQuery({ queryKey: ['budget-total', month], queryFn: () => request<BudgetTotal>(`/api/budgets/total?periodMonth=${month}`) });
  const expenseSummary = useQuery({ queryKey: ['budget-usage', month, 'expense-summary'], queryFn: () => request<{ expense?: string | null }>(`/api/budgets/expense-summary?periodMonth=${month}`) });
  const categories = useQuery({ queryKey: ['categories', 'flat-all-options'], queryFn: () => readAllPages(page => request<Page<Category>>(`/api/categories?projection=flat&page=${page}&size=50`, { responseType: 'page' })) });
  const members = useQuery({ queryKey: ['members'], queryFn: () => request<Member[]>('/api/members') });
  const revisions = useQuery({ queryKey: ['budget-revisions', historyId, revisionPage], queryFn: () => request<Page<BudgetRevision>>(`/api/budgets/${historyId}/revisions?page=${revisionPage}&size=50`, { responseType: 'page' }), enabled: historyId !== null });
  useEffect(() => { setUsagePage(0); }, [month, filter]);
  usePageRecovery(usagePage, usage.data, setUsagePage);
  usePageRecovery(revisionPage, revisions.data, setRevisionPage);
  const save = useMutation({ mutationFn: (value: NonNullable<typeof draft>) => request<Budget>(value.id ? `/api/budgets/${value.id}` : '/api/budgets', { method: value.id ? 'PATCH' : 'POST', body: { periodMonth: value.periodMonth, scopeType: value.scopeType, categoryId: value.categoryId, memberId: value.memberId, amount: value.amount, note: value.note || null, active: value.active, version: value.id ? value.version : undefined } }), onSuccess: () => { setDraft(null); } });
  const saveTotal = useMutation({ mutationFn: (value: TotalDraft) => request<BudgetTotal>('/api/budgets/total', { method: 'PUT', body: { periodMonth: value.periodMonth, amount: value.amount, version: value.version } }), onSuccess: () => { setTotalDraft(null); } });
  const queryClient = useQueryClient();
  const entries = useQuery({ queryKey: ['budget-entries', entriesId, entriesPage], queryFn: () => request<Page<BudgetUsageEntry>>(`/api/budgets/${entriesId}/usage-entries?page=${entriesPage}&size=50`, { responseType: 'page' }), enabled: entriesId !== null });
  const templates = useQuery({ queryKey: ['budget-templates'], queryFn: () => request<BudgetTemplate[]>('/api/budget-templates') });
  const saveTemplate = useMutation({ mutationFn: (name: string) => request<BudgetTemplate>('/api/budget-templates', { method: 'POST', body: { name, rows: rows.filter(item => item.budget.active).map(item => ({ scopeType: item.budget.scopeType, categoryId: item.budget.categoryId, memberId: item.budget.memberId, amount: item.budget.amount, note: item.budget.note || null })) } }), onSuccess: () => { setTemplateName(''); setTemplateMessage('模板已保存。'); queryClient.invalidateQueries({ queryKey: ['budget-templates'] }); }, onError: () => setTemplateMessage('保存失败，请检查本月预算与名称。') });
  const applyTemplate = useMutation({ mutationFn: (id: number) => request<{ periodMonth: string; copied: number; skipped: number }>(`/api/budget-templates/${id}/apply?periodMonth=${month}`, { method: 'POST' }), onSuccess: data => { setTemplateMessage(data.skipped ? `已应用 ${data.copied} 条，跳过 ${data.skipped} 条（本月已存在）。` : `已应用 ${data.copied} 条模板预算到本月。`); queryClient.invalidateQueries({ queryKey: ['budget-usage'] }); } });
  const deleteTemplate = useMutation({ mutationFn: (id: number) => request<void>(`/api/budget-templates/${id}`, { method: 'DELETE' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['budget-templates'] }) });
  const rows = allUsage.data ?? [];
  const allocated = rows.filter(item => item.budget.active && item.budget.scopeType === 'CATEGORY' && item.budget.memberId === null)
    .reduce((sum, item) => sum + parseCents(item.budget.amount), 0n);
  const totalAmount = total.data?.amount ? parseCents(total.data.amount) : null;
  const totalVersion = total.data?.version ?? 0;
  const actualSpent = !expenseSummary.error && expenseSummary.data?.expense != null ? parseCents(expenseSummary.data.expense) : null;
  const categoryName = (id: number | null) => categories.data?.find(item => item.id === id)?.name ?? '全部分类';
  const memberName = (id: number | null) => members.data?.find(item => item.id === id)?.name ?? '全家';
  const isObservation = (budget: Budget) => budget.scopeType === 'MEMBER' || budget.scopeType === 'CATEGORY_MEMBER';
  const scopeName = (budget: Budget) => budget.scopeType === 'CATEGORY' && budget.memberId === null
    ? categoryName(budget.categoryId)
    : budget.scopeType === 'CATEGORY_MEMBER'
      ? `${categoryName(budget.categoryId)} · ${memberName(budget.memberId)}`
      : budget.scopeType === 'MEMBER' ? memberName(budget.memberId) : '家庭总预算';
  const shareLabel = (part: bigint) => {
    if (!totalAmount) return '0%';
    const value = Number((part * 10000n) / totalAmount) / 100;
    return `${value.toFixed(2).replace(/\.?0+$/, '')}%`;
  };
  const segmentWidth = (part: bigint) => `${Math.max(0, Number((part * 100000n) / (totalAmount ?? 1n)) / 1000)}%`;
  const categoryById = new Map<number, Category>((categories.data ?? []).map(item => [item.id, item]));
  const poolRows = rows.filter(item => item.budget.active && item.budget.scopeType === 'CATEGORY' && item.budget.memberId === null && parseCents(item.budget.amount) > 0n);
  const bigSegments: Array<{ key: number; name: string; color: string; amount: bigint }> = [];
  let otherAmount = 0n;
  let otherCount = 0;
  for (const item of poolRows) {
    const amount = parseCents(item.budget.amount);
    const share = totalAmount ? Number((amount * 10000n) / totalAmount) / 100 : 0;
    const category = item.budget.categoryId != null ? categoryById.get(item.budget.categoryId) : undefined;
    if (share > 1) {
      bigSegments.push({ key: item.budget.id, name: category?.name ?? '全部分类', color: SEGMENT_PALETTE[bigSegments.length % SEGMENT_PALETTE.length], amount });
    } else {
      otherAmount += amount;
      otherCount += 1;
    }
  }
  const remaining = totalAmount !== null && allocated <= totalAmount ? totalAmount - allocated : 0n;

  return <PageScaffold title="费用预算" description="顶部月度总预算为家庭总额；分类预算计入总额并受其约束，成员项为观察线、不占总额。" primaryAction={manager ? { label: '新建预算', onClick: () => setDraft({ periodMonth: month, scopeType: 'CATEGORY', categoryId: null, memberId: null, amount: '', version: 0, active: true, note: '' }) } : undefined} readonly={!manager}>
    {manager && <section className="budget-total-bar" aria-label="月度总预算">
      <QueryState loading={total.isLoading || allUsage.isLoading} error={total.error ?? allUsage.error}><div className="budget-total-head"><strong>月度总预算</strong>
        {totalAmount === null
          ? <button type="button" className="toolbar-button" onClick={() => setTotalDraft({ periodMonth: month, amount: '', version: 0 })}>设置总预算</button>
          : <button type="button" className="toolbar-button" onClick={() => setTotalDraft({ periodMonth: month, amount: formatYuan(totalAmount), version: totalVersion })}>调整总预算</button>}
      </div>
      {totalAmount !== null && <>
        <div className="budget-total-values"><span>总额 {money(formatYuan(totalAmount))}</span><span>已分配 {money(formatYuan(allocated))}</span><span>剩余可分配 {money(formatYuan(remaining))}</span><span>实际费用 {money(actualSpent == null ? null : formatYuan(actualSpent))}</span></div>
        <div className="budget-segment-bar" aria-label="分类预算占总预算的分段">
          {bigSegments.map(segment => <i key={segment.key} data-tip={`${segment.name} · 预算 ${money(formatYuan(segment.amount))} · 占总预算 ${shareLabel(segment.amount)}`} style={{ width: segmentWidth(segment.amount), background: segment.color }} />)}
          {otherAmount > 0n && <i data-tip={`其他 · ${otherCount} 个分类（各占总预算不超过 1%）合计 预算 ${money(formatYuan(otherAmount))} · 占总预算 ${shareLabel(otherAmount)}`} style={{ width: segmentWidth(otherAmount), background: OTHER_COLOR }} />}
          {remaining > 0n && <i data-tip={`未分配 · 剩余可分配 ${money(formatYuan(remaining))} · 占总预算 ${shareLabel(remaining)}`} style={{ width: segmentWidth(remaining), background: UNALLOCATED_COLOR }} />}
        </div>
        {allocated > totalAmount && <p role="alert" className="field-help">分类预算合计已超过月度总预算，请先调整总预算或缩减分类预算。</p>}
        {actualSpent !== null && actualSpent > totalAmount && <p role="alert" className="field-help">本月实际费用已超过总预算。</p>}
      </>}
      {totalAmount === null && <p className="field-help">尚未设置本月总预算：设置后分类预算的“已分配”合计将受总预算约束。</p>}
    <QueryState loading={expenseSummary.isLoading} error={expenseSummary.error}>{null}</QueryState></QueryState></section>}
    <div className="toolbar budget-toolbar"><label>预算月份<DateField mode="month" allowClear={false} value={month} onChange={e => { if(e.target.value)setMonth(e.target.value); }} /></label>
      <div className="budget-toolbar-actions">{manager && <button type="button" className="toolbar-button" onClick={() => { setTemplateOpen(true); setTemplateMessage(null); }}>预算模板</button>}<a className="toolbar-button" href={`/api/budgets/export.csv?periodMonth=${month}`} download>预算 CSV</a></div></div>
    <nav className="segmented-tabs" aria-label="预算状态"><button className={filter==='all'?'active':''} onClick={()=>setFilter('all')}>全部预算</button><button className={filter==='active'?'active':''} onClick={()=>setFilter('active')}>使用中</button><button className={filter==='inactive'?'active':''} onClick={()=>setFilter('inactive')}>已停用</button></nav>
    <QueryState loading={usage.isLoading} error={usage.error} empty={!usage.data?.items.length && usagePage === 0} emptyTitle={filter==='all'?'这个月还没有预算':'该状态下没有预算'} emptyDetail={manager ? '可先在上方设置月度总预算，再新建分类预算或成员观察线。' : '家庭管理员还没有设置本月预算。'}>
      <><div className="budget-grid">{usage.data?.items.map(item => { const percent = Math.max(0, Math.min(100, Number(item.percent))); const tone = !item.budget.active ? 'neutral' : item.status === 'OVER_BUDGET' ? 'danger' : item.status === 'NEAR_LIMIT' || item.status === 'AT_LIMIT' ? 'warning' : 'success'; return <article className="budget-card" key={item.budget.id}><header><div><span>{item.budget.periodMonth}</span><h2>{scopeName(item.budget)}</h2>{isObservation(item.budget) && <small>成员观察线 · 不占家庭总预算</small>}{item.budget.note ? <small>备注：{item.budget.note}</small> : null}</div><StatusTag tone={tone}>{!item.budget.active ? '已停用' : item.status === 'OVER_BUDGET' ? '已超支' : item.status === 'NEAR_LIMIT' ? '接近额度' : item.status === 'AT_LIMIT' ? '已用完' : '进度正常'}</StatusTag></header><div className="budget-values"><strong>{money(item.spent)}</strong><span> / {money(item.budget.amount)}</span></div><div className="progress-track" aria-label={`预算使用 ${item.percent}%`}><i style={{ width: `${percent}%` }} className={tone} /></div><footer><span>剩余 {money(item.remaining)}</span><span>{item.percent}%</span></footer>{manager && <div className="inline-actions"><button onClick={() => setDraft({ ...item.budget, note: item.budget.note ?? '' })}>调整预算</button><button onClick={() => { setEntriesPage(0); setEntriesId(item.budget.id); }}>查看明细</button><button onClick={() => { setRevisionPage(0); setHistoryId(item.budget.id); }}>修订记录</button></div>}</article>; })}</div><PaginationControls page={usagePage} totalPages={usage.data?.totalPages ?? 0} hasNext={usage.data?.hasNext ?? false} onPageChange={setUsagePage} label="预算" /></>
    </QueryState>
    <CenteredModal draft={draft} sessionKey={draft?.id} busy={save.isPending} onSessionStart={save.reset} open={draft !== null} title={draft && 'id' in draft && draft.id ? '调整预算' : '新建预算'} description="每次调整都会保留不可变修订记录；分类预算合计不能超过月度总预算。" onClose={() => setDraft(null)}>{draft && <form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); save.mutate(draft); }}><FormError error={save.error} /><label>月份<DateField name="periodMonth" mode="month" allowClear={false} value={draft.periodMonth} onChange={e => setDraft({ ...draft, periodMonth: e.target.value })} required /></label><label>预算范围<select name="scopeType" value={draft.scopeType} onChange={e => setDraft({ ...draft, scopeType: e.target.value as BudgetScopeType, categoryId: null, memberId: null })}><option value="CATEGORY">分类预算（计入家庭总额）</option><option value="CATEGORY_MEMBER">分类 + 成员（观察线）</option><option value="MEMBER">成员预算（观察线）</option></select></label>{draft.scopeType !== 'MEMBER' && <label>支出分类<select name="categoryId" required value={draft.categoryId ?? ''} onChange={e => setDraft({ ...draft, categoryId: Number(e.target.value) })}><option value="">请选择</option>{categories.data?.filter(item => item.kind === 'expense' && item.id > 0).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}{draft.scopeType !== 'CATEGORY' && <label>成员<select name="memberId" required value={draft.memberId ?? ''} onChange={e => setDraft({ ...draft, memberId: Number(e.target.value) })}><option value="">请选择</option>{members.data?.filter(item => item.id > 0).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}{draft.scopeType === 'CATEGORY' ? <p className="field-help">该分类全部支出均计入；金额计入总览条的“已分配”，受月度总预算约束。</p> : <p className="field-help">成员项为观察线：只统计对应成员（或该分类中该成员）的支出，不占家庭总预算额度。</p>}<label>预算金额<input name="amount" inputMode="decimal" required value={draft.amount} onChange={e => setDraft({ ...draft, amount: e.target.value })} /></label><label>备注<input name="note" maxLength={200} value={draft.note} onChange={e => setDraft({ ...draft, note: e.target.value })} placeholder="选填（≤200 字），如：五一假期调高" /></label>{'id' in draft && draft.id && <label className="switch-line"><input name="active" type="checkbox" checked={draft.active} onChange={e => setDraft({ ...draft, active: e.target.checked })} />启用该预算</label>}<Button htmlType="submit" theme="solid" type="primary" loading={save.isPending}>保存预算</Button></form>}</CenteredModal>
    <CenteredModal draft={totalDraft} sessionKey="budget-total" busy={saveTotal.isPending} onSessionStart={saveTotal.reset} open={totalDraft !== null} title="设置月度总预算" description="总预算不能低于已分配的分类预算合计。" onClose={() => setTotalDraft(null)}>{totalDraft && <form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); saveTotal.mutate(totalDraft); }}><FormError error={saveTotal.error} /><label>月份<DateField name="periodMonth" mode="month" value={totalDraft.periodMonth} onChange={() => {}} disabled /></label><label>总预算金额<input name="amount" inputMode="decimal" required autoFocus value={totalDraft.amount} onChange={e => setTotalDraft({ ...totalDraft, amount: e.target.value })} /></label><p className="field-help">已分配分类预算合计 {money(formatYuan(allocated))}；设置值不能低于该合计。</p><Button htmlType="submit" theme="solid" type="primary" loading={saveTotal.isPending}>保存总预算</Button></form>}</CenteredModal>
    <CenteredModal open={templateOpen} title="预算模板" onClose={() => setTemplateOpen(false)}><FormError error={saveTemplate.error || applyTemplate.error || deleteTemplate.error} /><QueryState loading={templates.isLoading} error={templates.error} empty={!templates.data?.length} emptyTitle="还没有预算模板"><div className="template-list">{templates.data?.map(template => <article key={template.id}><div><strong>{template.name}</strong><small>{template.rows.length} 条预算 · {new Date(template.createdAt).toLocaleDateString('zh-CN')}</small></div><span><button className="text-action" disabled={applyTemplate.isPending} onClick={() => { setTemplateMessage(null); applyTemplate.mutate(template.id); }}>应用到本月</button><button className="text-action danger" onClick={() => deleteTemplate.mutate(template.id)}>删除</button></span></article>)}</div></QueryState>{templateMessage && <p role="status" className="field-help">{templateMessage}</p>}<form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); const activeCount = rows.filter(item => item.budget.active).length; if (!templateName.trim()) { setTemplateMessage('请先填写模板名称。'); return; } if (activeCount === 0) { setTemplateMessage('本月还没有有效预算可保存：请先新建或复制预算。'); return; } saveTemplate.mutate(templateName.trim()); }}><FormError error={saveTemplate.error} /><label>模板名称<input value={templateName} onChange={e => setTemplateName(e.target.value)} maxLength={100} placeholder="如：日常月份" /></label><Button htmlType="submit" theme="solid" type="primary" loading={saveTemplate.isPending}>把本月预算存为模板{rows.some(item => item.budget.active) ? `（${rows.filter(item => item.budget.active).length} 条）` : ''}</Button></form></CenteredModal>
    <CenteredModal open={entriesId !== null} title="预算使用明细" onClose={() => { setEntriesId(null); setEntriesPage(0); }}><QueryState loading={entries.isLoading} error={entries.error} empty={!entries.data?.items.length && entriesPage === 0} emptyTitle="该预算本月没有命中支出"><><div className="budget-entry-list">{entries.data?.items.map(item => <article key={item.entryId}><div><strong>{item.categoryName}</strong>{item.memberName ? <small>{item.memberName}</small> : <small>家庭共同</small>}</div><div><b>{money(item.amount)}</b><small>{item.occurredOn}{item.note ? ` · ${item.note}` : ''}</small></div></article>)}</div><PaginationControls page={entriesPage} totalPages={entries.data?.totalPages ?? 0} hasNext={entries.data?.hasNext ?? false} onPageChange={setEntriesPage} label="使用明细" /></></QueryState></CenteredModal>
    <CenteredModal open={historyId !== null} title="预算修订记录" onClose={() => { setHistoryId(null); setRevisionPage(0); }}><QueryState loading={revisions.isLoading} error={revisions.error} empty={!revisions.data?.items.length && revisionPage === 0} emptyTitle="还没有修订"><><ol className="timeline-list">{revisions.data?.items.map(item => <li key={item.id}><i /><div><strong>{money(item.oldAmount)} → {money(item.newAmount)}</strong>{item.oldNote !== item.newNote && <p>备注：{item.oldNote ?? '（无）'} → {item.newNote ?? '（无）'}</p>}<p>{new Date(item.changedAt).toLocaleString('zh-CN')}</p></div></li>)}</ol><PaginationControls page={revisionPage} totalPages={revisions.data?.totalPages ?? 0} hasNext={revisions.data?.hasNext ?? false} onPageChange={setRevisionPage} label="预算修订" /></></QueryState></CenteredModal>
  </PageScaffold>;
}
