import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { Banknote, Briefcase, Car, CircleHelp, Gift, GraduationCap, HeartPulse, Home, ShoppingBag, Utensils, type LucideIcon } from 'lucide-react';
import { businessDate } from '../../shared/runtime';
import type {
  Account, Category, HouseholdRole, Member, Membership, Page,
  RecurringOccurrence, RecurringRule, RecurringScheduleType, TransactionKind,
} from '../../api/contracts';
import { PaginationControls, readAllPages, usePageRecovery } from '../../shared/pagination';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { DataPanel, Drawer, FormError, ModalDialog, PageScaffold, QueryState, StatusTag, isManager, money, type RequestFn } from '../common';

type RuleDraft = {
  id?: number; kind: TransactionKind; amount: string; scheduleType: RecurringScheduleType;
  intervalValue: number; dayOfMonth: number | null; dayOfWeek: string | null;
  startOn: string; endOn: string | null; accountId: string; memberId: string;
  categoryId: string; assignedUserId: string; paused: boolean;
};
type AnalysisPeriod = 'WEEK' | 'MONTH' | 'YEAR';

const RECURRING_PAGE_SIZE = 10;

export function RecurringPage({ request, role, userId }: { request: RequestFn; role: HouseholdRole; userId: number }) {
  const [section, setSection] = useState<'pending' | 'rules'>('pending');
  const [draft, setDraft] = useState<RuleDraft | null>(null);
  const [rulePage, setRulePage] = useState(0);
  const [occurrencePage, setOccurrencePage] = useState(0);
  const [selectedOccurrenceIds, setSelectedOccurrenceIds] = useState<number[]>([]);
  const [payment, setPayment] = useState<RecurringOccurrence | null>(null);
  const [ruleDetail, setRuleDetail] = useState<RecurringRule | null>(null);
  const [paymentAmount, setPaymentAmount] = useState('');
  const queryClient = useQueryClient();
  const manager = isManager(role);
  const fundsError = useFundsRefresh();
  const rules = useQuery({ queryKey: ['recurring-rules', 'page', rulePage, RECURRING_PAGE_SIZE], queryFn: () => request<Page<RecurringRule>>(`/api/recurring-rules?includeInactive=true&page=${rulePage}&size=${RECURRING_PAGE_SIZE}`, { responseType: 'page' }) });
  const ruleOptions = useQuery({ queryKey: ['recurring-rules', 'all-reference'], queryFn: () => readAllPages(page => request<Page<RecurringRule>>(`/api/recurring-rules?includeInactive=true&page=${page}&size=50`, { responseType: 'page' })) });
  const occurrences = useQuery({ queryKey: ['recurring-occurrences', 'pending-page', occurrencePage, RECURRING_PAGE_SIZE], queryFn: () => request<Page<RecurringOccurrence>>(`/api/recurring-occurrences?status=PENDING&page=${occurrencePage}&size=${RECURRING_PAGE_SIZE}`, { responseType: 'page' }) });
  const accounts = useQuery({ queryKey: ['accounts', 'all-options'], queryFn: () => readAllPages(page => request<Page<Account>>(`/api/accounts?page=${page}&size=50`, { responseType: 'page' })) });
  const categories = useQuery({ queryKey: ['categories', 'flat-all-options'], queryFn: () => readAllPages(page => request<Page<Category>>(`/api/categories?projection=flat&page=${page}&size=50`, { responseType: 'page' })) });
  const members = useQuery({ queryKey: ['members'], queryFn: () => request<Member[]>('/api/members') });
  const memberships = useQuery({ queryKey: ['memberships', 'all-options'], queryFn: () => readAllPages(page => request<Page<Membership>>(`/api/family/memberships?page=${page}&size=50`, { responseType: 'page' })) });
  const ruleMap = useMemo(() => new Map(ruleOptions.data?.map(rule => [rule.id, rule]) ?? []), [ruleOptions.data]);
  const pendingItems = occurrences.data?.items ?? [];
  const selectableItems = pendingItems.filter(item => item.assignedUserId === userId);
  const allSelected = selectableItems.length > 0 && selectableItems.every(item => selectedOccurrenceIds.includes(item.id));
  const overdueCount = pendingItems.filter(item => overdueDays(item.dueOn) > 0).length;
  usePageRecovery(rulePage, rules.data, setRulePage);
  usePageRecovery(occurrencePage, occurrences.data, setOccurrencePage);
  const refreshRecurring = () => {
    void queryClient.invalidateQueries({ queryKey: ['recurring-rules'] });
    void queryClient.invalidateQueries({ queryKey: ['recurring-occurrences'] });
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
  };
  const save = useMutation({
    mutationFn: (value: RuleDraft) => request<RecurringRule>(value.id ? `/api/recurring-rules/${value.id}` : '/api/recurring-rules', {
      method: value.id ? 'PATCH' : 'POST',
      body: { ...value, accountId: Number(value.accountId), memberId: Number(value.memberId), categoryId: Number(value.categoryId), assignedUserId: Number(value.assignedUserId), dayOfMonth: value.scheduleType === 'WEEKLY' ? null : value.dayOfMonth, dayOfWeek: value.scheduleType === 'WEEKLY' ? value.dayOfWeek : null },
    }),
    onSuccess: () => { setDraft(null); setSection('rules'); refreshRecurring(); },
  });
  const archive = useMutation({ mutationFn: (id: number) => request<void>(`/api/recurring-rules/${id}`, { method: 'DELETE' }), onSuccess: refreshRecurring });
  const confirm = useMutation({ mutationFn: (id: number) => request<RecurringOccurrence>(`/api/recurring-occurrences/${id}/confirm`, { method: 'POST', body: { amount: paymentAmount } }), onError: fundsError, onSuccess: () => { setPayment(null); setPaymentAmount(''); refreshRecurring(); } });
  const confirmBatch = useMutation({ mutationFn: (ids: number[]) => request('/api/recurring-occurrences/confirm', { method: 'POST', body: { occurrenceIds: ids } }), onError: fundsError, onSuccess: () => { setSelectedOccurrenceIds([]); refreshRecurring(); } });
  const skip = useMutation({ mutationFn: (id: number) => request<RecurringOccurrence>(`/api/recurring-occurrences/${id}/cancel`, { method: 'POST' }), onSuccess: (_data, id) => { setSelectedOccurrenceIds(ids => ids.filter(item => item !== id)); refreshRecurring(); } });
  const newRule = (): RuleDraft => {
    const eligible = (accounts.data ?? []).filter(account => account.openingConfirmed && account.openingOn && !account.archivedAt && (account.currency ?? 'CNY') === 'CNY');
    const confirmers = (memberships.data ?? []).filter(member => member.status === 'ACTIVE');
    return { kind: 'expense', amount: '', scheduleType: 'MONTHLY', intervalValue: 1, dayOfMonth: 1, dayOfWeek: null, startOn: businessDate(), endOn: null, accountId: eligible.length === 1 ? String(eligible[0].id) : '', memberId: members.data?.length === 1 ? String(members.data[0].id) : '', categoryId: '', assignedUserId: confirmers.length === 1 ? String(confirmers[0].userId) : '', paused: false };
  };
  const editRule = (item: RecurringRule): RuleDraft => ({ id: item.id, kind: item.kind, amount: item.amount, scheduleType: item.scheduleType, intervalValue: item.intervalValue, dayOfMonth: item.dayOfMonth, dayOfWeek: item.dayOfWeek, startOn: item.startOn, endOn: item.endOn, accountId: String(item.accountId), memberId: String(item.memberId), categoryId: String(item.categoryId), assignedUserId: String(item.assignedUserId), paused: item.paused });
  const cloneRule = (item: RecurringRule): RuleDraft => ({ ...editRule(item), id: undefined, startOn: businessDate(), endOn: null, paused: false });
  const openPayment = (item: RecurringOccurrence) => { setPayment(item); setPaymentAmount(ruleMap.get(item.ruleId)?.amount ?? ''); confirm.reset(); };
  const skipFromDetail = () => { if (!payment || skip.isPending) return; const id = payment.id; setPayment(null); skip.mutate(id); };
  const toggleSelected = (id: number) => setSelectedOccurrenceIds(ids => ids.includes(id) ? ids.filter(item => item !== id) : [...ids, id]);
  const toggleAll = () => setSelectedOccurrenceIds(allSelected ? [] : selectableItems.map(item => item.id));
  const changeOccurrencePage = (page: number) => { setSelectedOccurrenceIds([]); setOccurrencePage(page); };
  const paymentRule = payment ? ruleMap.get(payment.ruleId) : undefined;

  return <PageScaffold title="周期账单" description="把固定收入与支出变成可确认的计划，到期后由你决定何时入账。" primaryAction={manager ? { label: '新建周期规则', onClick: () => setDraft(newRule()) } : undefined}>
    <nav className="segmented-tabs recurring-tabs" aria-label="周期账单视图"><button className={section === 'pending' ? 'active' : ''} onClick={() => setSection('pending')}>待确认账单 <span className="tab-count">{occurrences.data?.totalElements ?? '—'}</span></button><button className={section === 'rules' ? 'active' : ''} onClick={() => setSection('rules')}>规则管理 <span className="tab-count">{rules.data?.totalElements ?? '—'}</span></button></nav>
    <FormError error={confirm.error || confirmBatch.error || skip.error || archive.error} />
    <RecurringOverview pendingCount={occurrences.data?.totalElements} selectableCount={selectableItems.length} activeRules={ruleOptions.data ?? []} loading={occurrences.isLoading || ruleOptions.isLoading} />
    <div className="recurring-view">
      {section === 'pending' && <DataPanel className="recurring-pending-panel" title="待确认" meta={`${occurrences.data?.totalElements ?? 0} 个发生项`} action={selectableItems.length > 0 ? <span><label><input type="checkbox" aria-label="全选当前页" checked={allSelected} onChange={toggleAll} /> 全选当前页</label>{selectedOccurrenceIds.length > 0 && <Button size="small" loading={confirmBatch.isPending} onClick={() => confirmBatch.mutate(selectedOccurrenceIds)}>批量确认 {selectedOccurrenceIds.length} 条</Button>}</span> : undefined}>
        <QueryState loading={occurrences.isLoading || ruleOptions.isLoading} error={occurrences.error || ruleOptions.error} empty={!pendingItems.length && occurrencePage === 0} emptyTitle="没有待确认账单"><><div className="task-list">{pendingItems.map(item => { const rule = ruleMap.get(item.ruleId); const allowed = item.assignedUserId === userId; const lateDays = overdueDays(item.dueOn); return <article className={`recurring-pending-card ${lateDays > 0 ? 'is-overdue' : ''}`} key={item.id}><div className="recurring-pending-main"><div className="recurring-item-heading"><CategoryIcon name={rule?.categoryName} kind={rule?.kind} /><h3>{rule?.categoryName ?? `规则 #${item.ruleId}`}</h3>{lateDays > 0 && <StatusTag tone="warning">逾期</StatusTag>}</div><time className="recurring-due-date" dateTime={item.dueOn}>{listDateText(item.dueOn)}</time></div><strong aria-label={`${kindLabel(rule?.kind)}金额`} className={`recurring-amount ${rule?.kind ?? ''}`}>{kindSign(rule?.kind)}{money(rule?.amount)}</strong>{allowed ? <div className="task-actions"><input type="checkbox" aria-label={`选择 ${item.dueOn}`} checked={selectedOccurrenceIds.includes(item.id)} onChange={() => toggleSelected(item.id)} /><Button size="small" theme="borderless" className="recurring-detail-action" onClick={() => openPayment(item)}>详情</Button><Button size="small" className="recurring-confirm-action" loading={confirm.isPending} onClick={() => openPayment(item)}>确认入账</Button></div> : <StatusTag>由其他成员确认</StatusTag>}</article>; })}</div><PaginationControls page={occurrencePage} totalPages={occurrences.data?.totalPages ?? 0} hasNext={occurrences.data?.hasNext ?? false} onPageChange={changeOccurrencePage} label="待确认账单" /></></QueryState>
      </DataPanel>}
      {section === 'rules' && <DataPanel className="recurring-rules-panel" title="周期规则" meta={`${rules.data?.totalElements ?? 0} 条规则`}><QueryState loading={rules.isLoading} error={rules.error} empty={!rules.data?.items.length && rulePage === 0} emptyTitle="还没有周期规则"><><div className="rule-list">{rules.data?.items.map(item => <article className={`recurring-rule-card ${item.kind} ${item.paused ? 'is-paused' : ''}`} key={item.id}><header><div><button type="button" className="recurring-rule-summary" onClick={() => setRuleDetail(item)}><CategoryIcon name={item.categoryName} kind={item.kind} /><span><h3>{item.categoryName}</h3><StatusTag tone={item.paused ? 'warning' : 'success'}>{!item.active ? '已归档' : item.paused ? '已暂停' : '执行中'}</StatusTag></span></button></div><strong aria-label={`${kindLabel(item.kind)}金额`} className={`recurring-amount ${item.kind}`}>{kindSign(item.kind)}{money(item.amount)}</strong></header><p>{scheduleText(item)} · 下次 {item.nextDueOn ?? '无'}</p><footer>{manager && item.active && <span><button className="recurring-rule-action" onClick={() => setRuleDetail(item)}>详情</button><button className="recurring-rule-action" onClick={() => setDraft(editRule(item))}>编辑</button><button className="recurring-rule-action" onClick={() => setDraft(cloneRule(item))}>复制</button><button className="recurring-rule-action danger" onClick={() => archive.mutate(item.id)}>归档</button></span>}</footer></article>)}</div><PaginationControls page={rulePage} totalPages={rules.data?.totalPages ?? 0} hasNext={rules.data?.hasNext ?? false} onPageChange={setRulePage} label="周期规则" /></></QueryState></DataPanel>}
    </div>
    <Drawer draft={draft} sessionKey={draft?.id} busy={save.isPending} onSessionStart={save.reset} open={draft !== null} title={draft?.id ? '编辑周期规则' : '新建周期规则'} onClose={() => setDraft(null)}>{draft && <form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); save.mutate(draft); }}><FormError error={save.error} /><fieldset className={`recurring-kind-selector ${draft.kind}`}><legend>收支类型</legend><p>{draft.kind === 'income' ? '收入：会增加家庭账户余额' : '支出：会减少家庭账户余额'}</p><label>类型<select name="kind" value={draft.kind} onChange={e => setDraft({ ...draft, kind: e.target.value as TransactionKind, categoryId: '' })}><option value="expense">支出</option><option value="income">收入</option></select></label></fieldset><label>金额<input name="amount" required inputMode="decimal" value={draft.amount} onChange={e => setDraft({ ...draft, amount: e.target.value })} /></label><label>频率<select name="scheduleType" value={draft.scheduleType} onChange={e => { const scheduleType = e.target.value as RecurringScheduleType; setDraft({ ...draft, scheduleType, dayOfMonth: scheduleType === 'WEEKLY' ? null : 1, dayOfWeek: scheduleType === 'WEEKLY' ? 'MONDAY' : null }); }}><option value="MONTHLY">每月</option><option value="QUARTERLY">每季度</option><option value="YEARLY">每年</option><option value="WEEKLY">每周</option></select></label><label>间隔<input name="intervalValue" type="number" min="1" max="24" value={draft.intervalValue} onChange={e => setDraft({ ...draft, intervalValue: Number(e.target.value) })} /></label>{draft.scheduleType === 'WEEKLY' ? <label>星期<select name="dayOfWeek" value={draft.dayOfWeek ?? 'MONDAY'} onChange={e => setDraft({ ...draft, dayOfWeek: e.target.value })}>{[['MONDAY', '周一'], ['TUESDAY', '周二'], ['WEDNESDAY', '周三'], ['THURSDAY', '周四'], ['FRIDAY', '周五'], ['SATURDAY', '周六'], ['SUNDAY', '周日']].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label> : <label>{draft.scheduleType === 'YEARLY' ? '每年日期' : draft.scheduleType === 'QUARTERLY' ? '季度日期' : '每月日期'}<input name="dayOfMonth" aria-label="每月日期" type="number" min="1" max="31" required value={draft.dayOfMonth ?? 1} onChange={e => setDraft({ ...draft, dayOfMonth: Number(e.target.value) })} /></label>}<label>开始日期<input name="startOn" type="date" value={draft.startOn} onChange={e => setDraft({ ...draft, startOn: e.target.value })} /></label><label>结束日期（可选）<input name="endOn" type="date" value={draft.endOn ?? ''} onChange={e => setDraft({ ...draft, endOn: e.target.value || null })} /></label><p className="field-help">保存后从开始日期计算首期账单；到期账单会先进入“待确认”，不会自动扣款。每月 31 日在没有 31 日的月份按月末处理。</p><label>账户<select name="accountId" required value={draft.accountId} onChange={e => setDraft({ ...draft, accountId: e.target.value })}><option value="">请选择</option><AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} /></select></label><label>分类<select name="categoryId" required value={draft.categoryId} onChange={e => setDraft({ ...draft, categoryId: e.target.value })}><option value="">请选择</option>{categories.data?.filter(item => item.kind === draft.kind).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>归属成员<select name="memberId" required value={draft.memberId} onChange={e => setDraft({ ...draft, memberId: e.target.value })}><option value="">请选择</option>{members.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>确认人<select name="assignedUserId" required value={draft.assignedUserId} onChange={e => setDraft({ ...draft, assignedUserId: e.target.value })}><option value="">请选择</option>{memberships.data?.map(item => <option key={item.userId} value={item.userId}>{item.displayName}</option>)}</select></label><label className="switch-line"><input name="paused" type="checkbox" checked={draft.paused} onChange={e => setDraft({ ...draft, paused: e.target.checked })} />暂停规则</label><Button htmlType="submit" theme="solid" type="primary" loading={save.isPending}>保存周期规则</Button></form>}</Drawer>
    <ModalDialog open={ruleDetail !== null} title="规则详情" description="查看这条周期规则的完整信息" className="recurring-detail-dialog" onClose={() => setRuleDetail(null)} footer={<><Button onClick={() => setRuleDetail(null)}>关闭</Button>{manager && ruleDetail?.active && <Button theme="solid" type="primary" onClick={() => { if (ruleDetail) setDraft(editRule(ruleDetail)); setRuleDetail(null); }}>编辑规则</Button>}</>}>{ruleDetail && <><div className="recurring-detail-summary"><CategoryIcon name={ruleDetail.categoryName} kind={ruleDetail.kind} /><div><strong>{ruleDetail.categoryName}</strong><span>{ruleDetail.paused ? '已暂停' : ruleDetail.active ? '执行中' : '已归档'}</span></div><strong aria-label={`${kindLabel(ruleDetail.kind)}金额`} className={`recurring-amount ${ruleDetail.kind}`}>{kindSign(ruleDetail.kind)}{money(ruleDetail.amount)}</strong></div><dl className="recurring-detail-list"><div><dt>执行周期</dt><dd>{scheduleText(ruleDetail)}</dd></div><div><dt>下次日期</dt><dd>{ruleDetail.nextDueOn ?? '无'}</dd></div><div><dt>账户</dt><dd>{ruleDetail.accountName}</dd></div><div><dt>归属成员</dt><dd>{ruleDetail.memberName}</dd></div><div><dt>确认人</dt><dd>{ruleDetail.assignedUserName}</dd></div></dl></>}</ModalDialog>
    <ModalDialog open={payment !== null} title="账单详情" description="确认前请核对本期账单信息" className="recurring-detail-dialog" onClose={() => { setPayment(null); setPaymentAmount(''); confirm.reset(); }} footer={<><Button loading={skip.isPending} onClick={skipFromDetail}>跳过本期</Button><Button theme="solid" type="primary" loading={confirm.isPending} disabled={payment?.assignedUserId !== userId} onClick={() => payment && confirm.mutate(payment.id)}>记录本次账单</Button></>}>{payment && <><FormError error={confirm.error} /><div className="recurring-detail-summary"><CategoryIcon name={paymentRule?.categoryName} kind={paymentRule?.kind} /><div><strong>{paymentRule?.categoryName ?? `规则 #${payment.ruleId}`}</strong><span>{overdueDays(payment.dueOn) > 0 ? `逾期 ${overdueDays(payment.dueOn)} 天` : '待确认'}</span></div><strong aria-label={`${kindLabel(paymentRule?.kind)}金额`} className={`recurring-amount ${paymentRule?.kind ?? ''}`}>{kindSign(paymentRule?.kind)}{money(paymentRule?.amount)}</strong></div><dl className="recurring-detail-list"><div><dt>到期日</dt><dd>{payment.dueOn}</dd></div><div><dt>账户</dt><dd>{paymentRule?.accountName ?? '账户信息不可用'}</dd></div><div><dt>归属成员</dt><dd>{paymentRule?.memberName ?? '未分配'}</dd></div><div><dt>确认人</dt><dd>{paymentRule?.assignedUserName ?? '未分配'}</dd></div><div><dt>周期</dt><dd>{paymentRule ? scheduleText(paymentRule) : '规则信息不可用'}</dd></div></dl><div className="recurring-detail-amount"><label htmlFor="paymentAmount">本期实际金额</label><input id="paymentAmount" name="paymentAmount" required inputMode="decimal" value={paymentAmount} onChange={e => setPaymentAmount(e.target.value)} /><p>修改金额只影响本期账单，不会改变后续周期规则。</p></div><PaymentPreview account={accounts.data?.find(a => a.id === paymentRule?.accountId)} amount={paymentAmount} incoming={paymentRule?.kind === 'income'} showNote={false} /></>}</ModalDialog>
  </PageScaffold>;
}

function RecurringOverview({ pendingCount, selectableCount, activeRules, loading }: {
  pendingCount?: number;
  selectableCount: number;
  activeRules: RecurringRule[];
  loading: boolean;
}) {
  const enabledRules = activeRules.filter(item => item.active && !item.paused);
  const income = enabledRules.filter(item => item.kind === 'income').reduce((total, item) => total + monthlyRuleAmount(item), 0);
  const expense = enabledRules.filter(item => item.kind === 'expense').reduce((total, item) => total + monthlyRuleAmount(item), 0);
  const net = income - expense;
  const categoryMap = new Map<number, { name: string; amount: number; count: number }>();
  enabledRules.filter(item => item.kind === 'expense').forEach(item => {
    const current = categoryMap.get(item.categoryId) ?? { name: item.categoryName, amount: 0, count: 0 };
    current.amount += monthlyRuleAmount(item);
    current.count += 1;
    categoryMap.set(item.categoryId, current);
  });
  const colors = ['#3370ff', '#5b8ff9', '#61cfa3', '#65789b', '#f6bd16'];
  const allCategories = [...categoryMap.values()].filter(item => item.amount > 0).sort((a, b) => b.amount - a.amount);
  const categoryTotal = allCategories.reduce((total, item) => total + item.amount, 0);
  const categoryData = allCategories.slice(0, 5).map((item, index) => ({ ...item, color: colors[index] }));
  const remainingCategories = allCategories.slice(5);
  if (remainingCategories.length) categoryData.push({
    name: '其他', color: '#a3adbc',
    amount: remainingCategories.reduce((total, item) => total + item.amount, 0),
    count: remainingCategories.reduce((total, item) => total + item.count, 0),
  });
  const topCategory = categoryData[0];
  let categoryStart = -90;
  const categorySegments = categoryData.map(item => {
    const startAngle = categoryStart;
    const endAngle = startAngle + item.amount / categoryTotal * 360;
    categoryStart = endAngle;
    return { ...item, startAngle, endAngle, percentage: item.amount / categoryTotal * 100 };
  });
  const [year, month] = businessDate().split('-');

  return <section className="recurring-overview" aria-label="周期账单概览">
    <header className="recurring-overview-top"><div><span className="recurring-overview-kicker">固定收支</span><h2>月度计划概览</h2><p>固定收入与支出按当前周期规则折算，提前看清本月安排。</p></div><span className="recurring-overview-period">{year}年{Number(month)}月</span></header>
    <div className="recurring-overview-summary">
      <div className={`recurring-summary-total ${net >= 0 ? 'positive' : 'negative'}`}><span>月度计划净额</span><strong>{loading ? '—' : `${net >= 0 ? '+' : '-'}${money(Math.abs(net).toFixed(2))}`}</strong><small>固定收入 − 固定支出</small></div>
      <div className="recurring-summary-stat"><span>固定收入</span><strong className="income">{loading ? '—' : money(income.toFixed(2))}</strong><small>{enabledRules.filter(item => item.kind === 'income').length} 条规则</small></div>
      <div className="recurring-summary-stat"><span>固定支出</span><strong className="expense">{loading ? '—' : money(expense.toFixed(2))}</strong><small>{enabledRules.filter(item => item.kind === 'expense').length} 条规则</small></div>
      <div className="recurring-summary-stat"><span>待确认账单</span><strong>{loading ? '—' : pendingCount ?? 0}</strong><small>{selectableCount ? `${selectableCount} 项由你处理` : '到期后由你确认'}</small></div>
    </div>
    <div className="recurring-expense-analysis"><div className="recurring-expense-analysis-heading"><span>分类分析</span><h3>支出分类</h3>{topCategory && <p>支出分类中，<strong>{topCategory.name}</strong>占比最高，共 <em>{(topCategory.amount / categoryTotal * 100).toFixed(1)}%</em>。</p>}</div>{categoryData.length ? <svg className="recurring-category-ring" viewBox="0 0 1300 250" role="img" aria-label={`支出分类：${categorySegments.map(item => `${item.name} ${item.percentage.toFixed(1)}%`).join('，')}`}><circle className="recurring-category-ring-track" cx="650" cy="125" r="105" />{categorySegments.map(item => { const fullRing = item.endAngle - item.startAngle >= 359.9; const path = fullRing ? '' : categoryArcPath(650, 125, 105, 62, item.startAngle, item.endAngle); const midAngle = fullRing ? 0 : (item.startAngle + item.endAngle) / 2; const anchor = categoryPolarPoint(650, 125, 115, midAngle); const right = anchor.x >= 650; const labelY = Math.max(24, Math.min(226, anchor.y)); const bendX = right ? 1050 : 250; const textX = right ? 1220 : 80; return <g key={item.name}>{fullRing ? <circle cx="650" cy="125" r="83" fill="none" stroke={item.color} strokeWidth="43" /> : <path className="recurring-category-ring-segment" d={path} fill={item.color} />}<path className="recurring-category-ring-leader" d={`M ${anchor.x.toFixed(1)} ${anchor.y.toFixed(1)} L ${bendX} ${anchor.y.toFixed(1)} L ${textX} ${labelY}`} /><circle className="recurring-category-ring-dot" cx={anchor.x} cy={anchor.y} r="2.5" fill={item.color} /><text className="recurring-category-ring-label" x={right ? textX + 6 : textX - 6} y={labelY} textAnchor={right ? 'start' : 'end'} dominantBaseline="middle"><tspan>{item.name} </tspan><tspan className="recurring-category-ring-value">{item.percentage.toFixed(1)}%</tspan></text></g>; })}<circle className="recurring-category-ring-hole" cx="650" cy="125" r="62" /><text className="recurring-category-ring-center-name" x="650" y="119" textAnchor="middle" dominantBaseline="middle">{topCategory?.name ?? '暂无'}</text><text className="recurring-category-ring-center-value" x="650" y="143" textAnchor="middle" dominantBaseline="middle">{topCategory ? `${(topCategory.amount / categoryTotal * 100).toFixed(1)}%` : '—'}</text></svg> : <p className="recurring-chart-empty">{loading ? '正在读取周期规则…' : '暂无执行中的支出规则'}</p>}</div>
  </section>;
}

function RecurringOverviewLegacy({ pendingCount, overdueCount, selectableCount, activeRules, loading }: {
  pendingCount?: number;
  overdueCount?: number;
  selectableCount: number;
  activeRules: RecurringRule[];
  loading: boolean;
}) {
  const [analysisPeriod, setAnalysisPeriod] = useState<AnalysisPeriod>('MONTH');
  const [categoryKind, setCategoryKind] = useState<TransactionKind>('expense');
  const enabledRules = activeRules.filter(item => item.active && !item.paused);
  const periodLabels: Record<AnalysisPeriod, string> = { WEEK: '周度', MONTH: '月度', YEAR: '年度' };
  const periodName = periodLabels[analysisPeriod];
  const periodIncome = enabledRules.filter(item => item.kind === 'income').reduce((total, item) => total + periodRuleAmount(item, analysisPeriod), 0);
  const periodExpense = enabledRules.filter(item => item.kind === 'expense').reduce((total, item) => total + periodRuleAmount(item, analysisPeriod), 0);
  const periodNet = periodIncome - periodExpense;
  const maxPlan = Math.max(1, periodIncome, periodExpense);
  const categoryMap = new Map<string, { name: string; kind: TransactionKind; amount: number; count: number }>();
  enabledRules.forEach(item => {
    if (item.kind !== categoryKind) return;
    const key = `${item.categoryId}-${item.kind}`;
    const current = categoryMap.get(key) ?? { name: item.categoryName, kind: item.kind, amount: 0, count: 0 };
    current.amount += periodRuleAmount(item, analysisPeriod);
    current.count += 1;
    categoryMap.set(key, current);
  });
  const categoryColors = ['#3370ff', '#5b8ff9', '#61ddaa', '#65789b', '#f6bd16'];
  const categoryData = [...categoryMap.values()].sort((a, b) => b.amount - a.amount).slice(0, 5).map((item, index) => ({ ...item, color: categoryColors[index] }));
  const categoryTotal = Math.max(1, categoryData.reduce((total, item) => total + item.amount, 0));
  const topCategory = categoryData[0];
  let categoryStart = -90;
  const categorySegments = categoryData.map(item => {
    const startAngle = categoryStart;
    const endAngle = startAngle + item.amount / categoryTotal * 360;
    categoryStart = endAngle;
    return { ...item, startAngle, endAngle, percentage: item.amount / categoryTotal * 100 };
  });
  const scheduleLabels: Record<RecurringScheduleType, string> = { MONTHLY: '每月', QUARTERLY: '每季度', YEARLY: '每年', WEEKLY: '每周' };
  const scheduleColors: Record<RecurringScheduleType, string> = { MONTHLY: '#4b6bee', QUARTERLY: '#8b78d1', YEARLY: '#e0a34c', WEEKLY: '#54a88b' };
  const scheduleData = (Object.keys(scheduleLabels) as RecurringScheduleType[]).map(type => ({ type, label: scheduleLabels[type], count: enabledRules.filter(item => item.scheduleType === type).length, color: scheduleColors[type] })).filter(item => item.count > 0);
  let scheduleStart = -90;
  const scheduleSegments = scheduleData.map(item => {
    const startAngle = scheduleStart;
    const endAngle = startAngle + item.count / Math.max(1, enabledRules.length) * 360;
    scheduleStart = endAngle;
    return { ...item, startAngle, endAngle };
  });
  const planRows = [{ key: 'income', label: '收入', value: periodIncome }, { key: 'expense', label: '支出', value: periodExpense }];
  const planLabel = loading ? '正在读取周期规则' : `${periodName}计划收入 ${money(periodIncome.toFixed(2))}，支出 ${money(periodExpense.toFixed(2))}`;
  const [currentYear, currentMonth] = businessDate().split('-');
  const currentMonthText = `${currentYear}年${Number(currentMonth)}月`;
  const categoryLabel = categoryKind === 'expense' ? '支出分类' : '收入分类';

  return <section className="recurring-overview" aria-label="周期账单概览">
    <header className="recurring-overview-top">
      <div className="recurring-overview-copy"><span className="section-kicker">固定收支</span><h2>{periodName}小结</h2><p>按当前执行中的周期规则折算，提前看清固定收支安排。</p></div>
      <div className="recurring-overview-period-tools"><div className="recurring-analysis-periods" role="tablist" aria-label="分析周期">{(Object.keys(periodLabels) as AnalysisPeriod[]).map(item => <button key={item} type="button" role="tab" aria-selected={analysisPeriod === item} className={analysisPeriod === item ? 'active' : ''} onClick={() => setAnalysisPeriod(item)}>{periodLabels[item]}</button>)}</div><span className="recurring-overview-period">{currentMonthText}</span></div>
    </header>
    <div className="recurring-overview-summary">
      <div className={`recurring-summary-total ${periodNet >= 0 ? 'positive' : 'negative'}`}><span>{periodName}计划净额</span><strong>{loading ? '—' : `${periodNet >= 0 ? '+' : '-'}${money(Math.abs(periodNet).toFixed(2))}`}</strong><small>固定收入 − 固定支出</small></div>
      <div className="recurring-summary-stat"><span>固定收入</span><strong className="income">{loading ? '—' : money(periodIncome.toFixed(2))}</strong><small>{enabledRules.filter(item => item.kind === 'income').length} 条规则</small></div>
      <div className="recurring-summary-stat"><span>固定支出</span><strong className="expense">{loading ? '—' : money(periodExpense.toFixed(2))}</strong><small>{enabledRules.filter(item => item.kind === 'expense').length} 条规则</small></div>
      <div className="recurring-summary-stat"><span>待确认账单</span><strong>{loading ? '—' : pendingCount ?? 0}</strong><small>{selectableCount ? `${selectableCount} 项由你处理` : `当前页逾期 ${overdueCount ?? '—'} 项`}</small></div>
    </div>
    <div className="recurring-overview-charts">
      <article className="recurring-chart-card recurring-plan-chart"><div className="recurring-chart-heading"><div><span>收支概览</span><h3>{periodName}计划现金流</h3></div><small>按周期折算</small></div><div className="recurring-plan-bars" role="group" aria-label={planLabel}>{planRows.map(row => <div className="recurring-plan-row" key={row.key}><div><span>{row.label}</span><strong className={row.key}>{loading ? '—' : money(row.value.toFixed(2))}</strong></div><div className="recurring-plan-track"><i className={row.key} style={{ width: `${loading ? 0 : row.value / maxPlan * 100}%` }} /></div></div>)}</div><div className={`recurring-plan-net ${periodNet >= 0 ? 'positive' : 'negative'}`}><span>计划结余</span><strong>{loading ? '—' : `${periodNet >= 0 ? '+' : '-'}${money(Math.abs(periodNet).toFixed(2))}`}</strong></div><p className="recurring-chart-note">周度按 52 周、年度按 12 个月折算。</p></article>
      <article className="recurring-chart-card recurring-category-chart"><div className="recurring-chart-heading"><div><span>分类分析</span><h3>{categoryLabel}</h3></div><div className="recurring-category-switch" role="tablist" aria-label="收支分类"><button type="button" role="tab" aria-selected={categoryKind === 'expense'} className={categoryKind === 'expense' ? 'active' : ''} onClick={() => setCategoryKind('expense')}>支出</button><button type="button" role="tab" aria-selected={categoryKind === 'income'} className={categoryKind === 'income' ? 'active' : ''} onClick={() => setCategoryKind('income')}>收入</button></div></div>{categoryData.length ? <><p className="recurring-category-insight">{categoryLabel}中，<strong>{topCategory?.name}</strong>占比最高，共 <em>{topCategory ? `${(topCategory.amount / categoryTotal * 100).toFixed(1)}%` : '—'}</em>。</p><div className="recurring-category-visual"><svg className="recurring-category-ring" viewBox="0 0 620 250" role="img" aria-label={`${categoryLabel}：${categorySegments.map(item => `${item.name} ${item.percentage.toFixed(1)}%`).join('，')}`}><circle className="recurring-category-ring-track" cx="310" cy="125" r="90" /><>{categorySegments.map((item, index) => { const fullRing = item.endAngle - item.startAngle >= 359.9; const midAngle = (item.startAngle + item.endAngle) / 2; const anchor = categoryPolarPoint(310, 125, 96, midAngle); const right = anchor.x >= 310; const labelY = Math.max(28, Math.min(222, anchor.y)); const bendX = right ? 410 : 210; const textX = right ? 430 : 190; const path = fullRing ? '' : categoryArcPath(310, 125, 90, 54, item.startAngle, item.endAngle); return <g key={`${item.name}-${item.kind}`}><>{fullRing ? <circle cx="310" cy="125" r="72" fill="none" stroke={item.color} strokeWidth="36" /> : <path className="recurring-category-ring-segment" d={path} fill={item.color} />}</><path className="recurring-category-ring-leader" d={`M ${anchor.x.toFixed(1)} ${anchor.y.toFixed(1)} L ${bendX} ${anchor.y.toFixed(1)} L ${textX} ${labelY}`} /><circle className="recurring-category-ring-dot" cx={anchor.x} cy={anchor.y} r="2.5" fill={item.color} /><text className="recurring-category-ring-label" x={right ? textX + 6 : textX - 6} y={labelY} textAnchor={right ? 'start' : 'end'} dominantBaseline="middle"><tspan>{item.name} </tspan><tspan className="recurring-category-ring-value">{item.percentage.toFixed(1)}%</tspan></text></g>; })}</><circle className="recurring-category-ring-hole" cx="310" cy="125" r="54" /><text className="recurring-category-ring-center-name" x="310" y="119" textAnchor="middle" dominantBaseline="middle">{topCategory?.name ?? '暂无'}</text><text className="recurring-category-ring-center-value" x="310" y="143" textAnchor="middle" dominantBaseline="middle">{topCategory ? `${(topCategory.amount / categoryTotal * 100).toFixed(1)}%` : '—'}</text></svg></div></> : <p className="recurring-chart-empty">{loading ? '正在读取周期规则…' : `暂无执行中的${categoryLabel}`}</p>}<p className="recurring-chart-note">按{periodName}计划统计金额最高的 5 个分类。</p></article>
      <article className="recurring-chart-card recurring-schedule-chart"><div className="recurring-chart-heading"><div><span>执行方式</span><h3>执行节奏</h3></div><small>{enabledRules.length} 条</small></div><div className="recurring-schedule-content"><svg className="recurring-schedule-ring" viewBox="0 0 300 150" role="img" aria-label={`执行节奏分布：${scheduleData.map(item => `${item.label} ${item.count} 条`).join('，') || '暂无执行中规则'}`}><circle className="recurring-schedule-ring-track" cx="150" cy="75" r="52" />{scheduleSegments.map(item => { const fullRing = item.endAngle - item.startAngle >= 359.9; const path = fullRing ? '' : categoryArcPath(150, 75, 52, 30, item.startAngle, item.endAngle); const midAngle = fullRing ? 0 : (item.startAngle + item.endAngle) / 2; const anchor = categoryPolarPoint(150, 75, 60, midAngle); const right = anchor.x >= 150; const labelY = Math.max(28, Math.min(122, anchor.y)); const bendX = right ? 214 : 86; const textX = right ? 220 : 80; return <g key={item.type}>{fullRing ? <circle cx="150" cy="75" r="42" fill="none" stroke={item.color} strokeWidth="20" /> : <path className="recurring-schedule-ring-segment" d={path} fill={item.color} />}<path className="recurring-schedule-ring-leader" d={`M ${anchor.x.toFixed(1)} ${anchor.y.toFixed(1)} L ${bendX} ${anchor.y.toFixed(1)} L ${textX} ${labelY}`} /><circle className="recurring-schedule-ring-dot" cx={anchor.x} cy={anchor.y} r="3" fill={item.color} /><text className="recurring-schedule-ring-label" x={textX} y={labelY} textAnchor={right ? 'start' : 'end'} dominantBaseline="middle"><tspan>{item.label}</tspan><tspan className="recurring-schedule-ring-value" dx="8">{item.count}</tspan></text></g>; })}<circle className="recurring-schedule-ring-hole" cx="150" cy="75" r="30" /><text className="recurring-schedule-ring-count" x="150" y="70" textAnchor="middle" dominantBaseline="middle">{loading ? '—' : enabledRules.length}</text><text className="recurring-schedule-ring-caption" x="150" y="89" textAnchor="middle" dominantBaseline="middle">执行中</text></svg></div><p className="recurring-chart-note">了解固定账单集中在哪些周期。</p></article>
    </div>
  </section>;
}

function monthlyRuleAmount(item: RecurringRule) {
  const amount = Number(item.amount);
  const interval = Math.max(1, item.intervalValue || 1);
  if (!Number.isFinite(amount)) return 0;
  if (item.scheduleType === 'QUARTERLY') return amount / (3 * interval);
  if (item.scheduleType === 'YEARLY') return amount / (12 * interval);
  if (item.scheduleType === 'WEEKLY') return amount * (52 / (12 * interval));
  return amount / interval;
}

function periodRuleAmount(item: RecurringRule, period: AnalysisPeriod) {
  const monthly = monthlyRuleAmount(item);
  if (period === 'WEEK') return monthly * (12 / 52);
  if (period === 'YEAR') return monthly * 12;
  return monthly;
}

function categoryPolarPoint(cx: number, cy: number, radius: number, angle: number) {
  const radians = (angle * Math.PI) / 180;
  return { x: cx + radius * Math.cos(radians), y: cy + radius * Math.sin(radians) };
}

function categoryArcPath(cx: number, cy: number, outerRadius: number, innerRadius: number, startAngle: number, endAngle: number) {
  const outerStart = categoryPolarPoint(cx, cy, outerRadius, startAngle);
  const outerEnd = categoryPolarPoint(cx, cy, outerRadius, endAngle);
  const innerEnd = categoryPolarPoint(cx, cy, innerRadius, endAngle);
  const innerStart = categoryPolarPoint(cx, cy, innerRadius, startAngle);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return `M ${outerStart.x.toFixed(2)} ${outerStart.y.toFixed(2)} A ${outerRadius} ${outerRadius} 0 ${largeArc} 1 ${outerEnd.x.toFixed(2)} ${outerEnd.y.toFixed(2)} L ${innerEnd.x.toFixed(2)} ${innerEnd.y.toFixed(2)} A ${innerRadius} ${innerRadius} 0 ${largeArc} 0 ${innerStart.x.toFixed(2)} ${innerStart.y.toFixed(2)} Z`;
}

function scheduleText(item: RecurringRule) {
  if (item.scheduleType === 'WEEKLY') return `每 ${item.intervalValue} 周 · ${item.dayOfWeek}`;
  const unit = item.scheduleType === 'YEARLY' ? '年' : item.scheduleType === 'QUARTERLY' ? '季度' : '个月';
  return `每 ${item.intervalValue} ${unit} · ${item.dayOfMonth} 日`;
}

function overdueDays(dueOn: string) {
  const today = Date.parse(`${businessDate()}T00:00:00Z`);
  const due = Date.parse(`${dueOn}T00:00:00Z`);
  return Math.max(0, Math.floor((today - due) / 86_400_000));
}

function listDateText(value: string) {
  const [year, month, day] = value.split('-');
  return `${year}年${Number(month)}月${Number(day)}日`;
}

function kindLabel(kind?: TransactionKind) {
  return kind === 'income' ? '收入' : kind === 'expense' ? '支出' : '类型未知';
}

function kindSign(kind?: TransactionKind) {
  return kind === 'income' ? '+' : kind === 'expense' ? '-' : '';
}

const categoryIconRules: Array<{ keywords: string[]; icon: LucideIcon }> = [
  { keywords: ['餐饮', '吃饭', '食品', '菜'], icon: Utensils },
  { keywords: ['交通', '出行', '汽车', '打车'], icon: Car },
  { keywords: ['购物', '消费', '日用'], icon: ShoppingBag },
  { keywords: ['教育', '学习', '培训'], icon: GraduationCap },
  { keywords: ['医疗', '健康', '药'], icon: HeartPulse },
  { keywords: ['居家', '住房', '房租', '家'], icon: Home },
  { keywords: ['工资', '薪资', '收入'], icon: Banknote },
  { keywords: ['奖金', '礼物', '红包'], icon: Gift },
  { keywords: ['工作', '业务', '职业'], icon: Briefcase },
];

function CategoryIcon({ name, kind }: { name?: string; kind?: TransactionKind }) {
  const match = categoryIconRules.find(rule => rule.keywords.some(keyword => name?.includes(keyword)));
  const Icon = match?.icon ?? CircleHelp;
  return <span className={`recurring-category-icon ${kind ?? 'unknown'}`} aria-hidden="true"><Icon size={15} strokeWidth={2.2} /></span>;
}
