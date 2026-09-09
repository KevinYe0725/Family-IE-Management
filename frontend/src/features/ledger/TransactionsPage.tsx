import {BankAccountPicker} from './BankAccountPicker';
import {BankAccountsPanel} from './BankAccountsPanel';
import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {LedgerCharts} from './LedgerCharts';
import {BankTransactionsDialog,TransactionDetailsDialog} from './TransactionDetailsDialog';
import './ledger-workspace.scss';
import { Download } from 'lucide-react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, AccountType, BudgetHit, WalletProvider, Category, HouseholdRole, Member, Page, Transaction, TransactionKind } from '../../api/contracts';
import { businessDate, localYearMonth, newIdempotencyKey } from '../../shared/runtime';
import { DateField } from '../../shared/DateField';
import { PaginationControls, readAllPages, usePageRecovery } from '../../shared/pagination';
import { AccountOptions, PaymentPreview, useFundsRefresh } from '../accounting';
import { accountDescription, accountLabel } from './account-label';
import { AccountIcon } from './AccountIdentity';
import { AccountTypePicker } from './AccountTypePicker';
import { FxTransfersPanel } from './FxTransfersPanel';
import { AccountingHistory, TransfersPanel } from './accounting-flows';
import { ConfirmDialog, DataPanel, ActionDialog, FormError, PageScaffold, QueryState, StatusTag, isManager, money, type RequestFn } from '../common';

interface TransactionDraft { key: string; generated?: boolean; principalAmount?: string | null; interestAmount?: string | null; id?: number; kind: TransactionKind; amount: string; occurredOn: string; accountId: string; memberId: string; categoryId: string; merchant: string; location: string; note: string }
const emptyDraft = (): TransactionDraft => ({ key: newIdempotencyKey(), kind: 'expense', amount: '', occurredOn: businessDate(), accountId: '', memberId: '', categoryId: '', merchant: '', location: '', note: '' });

export function TransactionsPage({ request, role, userId, requestedSection }: { request: RequestFn; role: HouseholdRole; userId: number; requestedSection?: 'accounts' }) {
  const [section, setSection] = useState<'transactions' | 'accounts' | 'categories' | 'transfers' | 'history'>(() => requestedSection === 'accounts' || new URLSearchParams(window.location.search).get('section') === 'accounts' ? 'accounts' : 'transactions');
  useEffect(() => { if (requestedSection === 'accounts') setSection('accounts'); }, [requestedSection]);
  const currencyOptions=useQuery({queryKey:['currency-capabilities'],queryFn:()=>request<{currencies:string[];deploymentReady?:boolean}>('/api/currencies'),refetchInterval:query=>query.state.data?.deploymentReady===false?5000:false});
  const supportedCurrencies=currencyOptions.data?.currencies??['CNY'];
  const [month, setMonth] = useState(localYearMonth());
  const [selectedDay,setSelectedDay]=useState<string|null>(null);
  const [detail,setDetail]=useState<Transaction|null>(null);
  const [archiveCandidate,setArchiveCandidate]=useState<Account|null>(null);
  const [categoryCandidate,setCategoryCandidate]=useState<Category|null>(null);
  const [bankRecords,setBankRecords]=useState<number|null>(null);
  const [bankOverlay,setBankOverlay]=useState(false);
  const [transferOverlay,setTransferOverlay]=useState(false);
  const [fxOverlay,setFxOverlay]=useState(false);
  useEffect(()=>{setSelectedDay(null);},[month]);
  const [kind, setKind] = useState('');
  const [q, setQ] = useState('');
  const [accountId, setAccountId] = useState('');
  const [bankAccountId,setBankAccountId]=useState('');
  const [exchangeBankId,setExchangeBankId]=useState<number|null>(null);
  const [memberId, setMemberId] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [transactionPage, setTransactionPage] = useState(0);
  const [accountPage, setAccountPage] = useState(0);
  const [categoryPage, setCategoryPage] = useState(0);
  const [draft, setDraft] = useState<TransactionDraft | null>(() => !requestedSection && section === 'transactions' && new URLSearchParams(window.location.search).get('create') === '1' ? emptyDraft() : null);
  useEffect(() => {
    const url = new URL(window.location.href);
    if (!requestedSection && url.searchParams.get('create') === '1') {
      url.searchParams.delete('create');
      window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
    }
  }, [requestedSection]);
  const [deleting, setDeleting] = useState<{ id: number; key: string } | null>(null);
  const deleteId = deleting?.id ?? null;
  const setDeleteId = (id: number | null) => setDeleting(id === null ? null : { id, key: newIdempotencyKey() });
  const [accountDraft, setAccountDraft] = useState<{ id?: number; name: string; type: AccountType; walletProvider: WalletProvider | ''; bankName: string; cardLastFour: string; currency: string; openingBalance: string; openingOn: string; mode: 'create' | 'metadata' | 'opening'; confirmed: boolean; key: string } | null>(null);
  const [categoryDraft, setCategoryDraft] = useState<{ id?: number; name: string; kind: TransactionKind; color: string; parentId: string } | null>(null);

  const filters = useMemo(() => new URLSearchParams(Object.entries({ month: selectedDay ? '' : month, from: selectedDay ?? '', to: selectedDay ?? '', kind, q, accountId, bankAccountId, memberId, categoryId }).filter(([, value]) => value)).toString(), [month, selectedDay, kind, q, accountId, bankAccountId, memberId, categoryId]);
  const transactions = useQuery({ queryKey: ['transactions', 'page', filters, transactionPage], queryFn: () => request<Page<Transaction>>(`/api/transactions?${filters}&page=${transactionPage}&size=50`, { responseType: 'page' }) });

  const accountOptions = useQuery({ queryKey: ['accounts', 'all-options'], queryFn: () => readAllPages(page => request<Page<Account>>(`/api/accounts?page=${page}&size=50`, { responseType: 'page' })) });
  const standaloneAccounts=accountOptions.data?.filter(a=>a.type!=='BANK'||!a.bankAccountId);
  const standalonePage=standaloneAccounts?{items:standaloneAccounts.slice(accountPage*50,(accountPage+1)*50),page:accountPage,size:50,totalElements:standaloneAccounts.length,totalPages:Math.ceil(standaloneAccounts.length/50),hasNext:(accountPage+1)*50<standaloneAccounts.length}:undefined;
  const categories = useQuery({ queryKey: ['categories', 'tree-page', categoryPage], queryFn: () => request<Page<Category>>(`/api/categories?projection=tree&page=${categoryPage}&size=50`, { responseType: 'page' }) });
  const categoryOptions = useQuery({ queryKey: ['categories', 'flat-all-options'], queryFn: () => readAllPages(page => request<Page<Category>>(`/api/categories?projection=flat&page=${page}&size=50`, { responseType: 'page' })) });
  const members = useQuery({ queryKey: ['members'], queryFn: () => request<Member[]>('/api/members') });
  const orderedCategories = useMemo(() => (categories.data?.items ?? []).map(item => ({
    ...item,
    children: [...(item.children ?? [])].sort((left, right) => left.id - right.id)
  })).sort((left, right) => left.id - right.id), [categories.data]);
  const flatCategories = categoryOptions.data ?? [];
  function newTransaction() {
    const eligible = (accountOptions.data ?? []).filter(account => account.openingConfirmed && account.openingOn && !account.archivedAt);
    setDraft({ ...emptyDraft(), accountId: eligible.length === 1 ? String(eligible[0].id) : '', memberId: members.data?.length === 1 ? String(members.data[0].id) : '' });
  }
  const parentCategories = flatCategories.filter(item => item.parentId === null);
  const exportHref = `/api/export.csv${filters ? `?${filters}` : ''}`;
  useEffect(() => { setTransactionPage(0); }, [filters]);
  usePageRecovery(transactionPage, transactions.data, setTransactionPage);
  usePageRecovery(accountPage, standalonePage, setAccountPage);
  usePageRecovery(categoryPage, categories.data, setCategoryPage);

  const fundsError = useFundsRefresh();
  const [historySource, setHistorySource] = useState<{ sourceType: string; sourceId: number } | undefined>();
  const save = useMutation({
    mutationFn: (value: TransactionDraft) => request<Transaction>(value.id ? `/api/transactions/${value.id}` : '/api/transactions', {
      method: value.id ? 'PATCH' : 'POST', headers: { 'Idempotency-Key': value.key },
      body: value.generated ? { merchant: value.merchant || null, location: value.location || null, note: value.note || null } : { kind: value.kind, amount: value.amount, occurredOn: value.occurredOn, accountId: Number(value.accountId), memberId: Number(value.memberId), categoryId: Number(value.categoryId), merchant: value.merchant || null, location: value.location || null, note: value.note || null }
    }),
    onError: fundsError,
    onSuccess: () => { setDraft(null); setTransactionPage(0); }
  });
  const remove = useMutation({ mutationFn: (id: number) => request<void>(`/api/transactions/${id}`, { method: 'DELETE', headers: { 'Idempotency-Key': deleting!.key } }), onError: fundsError, onSuccess: () => { setDeleteId(null); setTransactionPage(0); } });
  const saveAccount = useMutation({ mutationFn: (value: NonNullable<typeof accountDraft>) => request<Account>(value.id ? `/api/accounts/${value.id}` : '/api/accounts', { method: value.id ? 'PATCH' : 'POST', headers: { 'Idempotency-Key': value.key }, body: value.mode === 'opening' ? { openingBalance: value.openingBalance, openingOn: value.openingOn } : value.mode === 'metadata' ? { name: value.name, type: value.type, walletProvider: value.walletProvider || null, bankName: value.bankName, cardLastFour: value.cardLastFour } : { name: value.name, type: value.type, walletProvider: value.walletProvider || null, bankName: value.bankName, cardLastFour: value.cardLastFour, currency: value.currency, openingBalance: value.openingBalance, openingOn: value.openingOn } }), onError: fundsError, onSuccess: () => { setAccountDraft(null); } });
  const archiveAccount = useMutation({ mutationFn: (id: number) => request<void>(`/api/accounts/${id}`, { method: 'DELETE' }), onSuccess:()=>setArchiveCandidate(null) });
  const saveCategory = useMutation({ mutationFn: (value: NonNullable<typeof categoryDraft>) => request<Category>(value.id ? `/api/categories/${value.id}` : '/api/categories', { method: value.id ? 'PATCH' : 'POST', body: { name: value.name, kind: value.kind, color: value.color, parentId: value.parentId ? Number(value.parentId) : null } }), onSuccess: () => { setCategoryDraft(null); } });
  const deleteCategory = useMutation({ mutationFn: (id: number) => request<void>(`/api/categories/${id}`, { method: 'DELETE' }), onSuccess:()=>setCategoryCandidate(null) });

  function editTransaction(item: Transaction) { setDraft({ key: newIdempotencyKey(), generated: item.sourceType !== 'MANUAL', principalAmount: item.principalAmount, interestAmount: item.interestAmount, id: item.id, kind: item.kind, amount: item.amount, occurredOn: item.occurredOn, accountId: String(item.accountId), memberId: item.memberId ? String(item.memberId) : '0', categoryId: String(item.categoryId), merchant: item.merchant ?? '', location: item.location ?? '', note: item.note ?? '' }); }
  const manager = isManager(role);
  const canEdit = (item: Transaction) => manager || item.createdByUserId === userId;
  const canDelete = (item: Transaction) => canEdit(item) && item.sourceType === 'MANUAL';
  const action = section === 'transactions' ? { label: '记一笔', onClick: newTransaction } : section === 'accounts' && manager ? { label: '新建现金或钱包', onClick: () => setAccountDraft({ name: '', type: 'CASH', walletProvider: '', bankName: '', cardLastFour: '', currency: 'CNY', openingBalance: '0.00', openingOn: businessDate(), mode: 'create', confirmed: false, key: newIdempotencyKey() }) } : section === 'categories' && manager ? { label: '新建分类', onClick: () => setCategoryDraft({ name: '', kind: 'expense', color: '#3370FF', parentId: '' }) } : undefined;

  return <PageScaffold title="收支明细" primaryAction={{label:"记一笔",onClick:newTransaction}} className="ledger-workspace">
    <nav className="ledger-tools" aria-label="账本模块"><Button onClick={()=>setSection('accounts')}>账户</Button><Button onClick={()=>setSection('categories')}>分类</Button></nav>
    <LedgerCharts categories={flatCategories} request={request} filters={filters} kind={kind==='income'?'income':'expense'} selectedDay={selectedDay} onKind={value=>{setKind(value);setCategoryId('');setSelectedDay(null);}} onCategory={id=>{setCategoryId(id?String(id):'');setSelectedDay(null);}} onDay={setSelectedDay}/>
    <>
      <div className="filter-bar">
        <label>账期<DateField aria-label="账期" mode="month" allowClear={false} value={month} onChange={e => setMonth(e.target.value)} /></label>
        <label>类型<select aria-label="收支类型筛选" value={kind} onChange={e => setKind(e.target.value)}><option value="">全部</option><option value="expense">支出</option><option value="income">收入</option></select></label>
<label>银行卡<select aria-label="银行卡筛选" value={bankAccountId} onChange={e=>{setBankAccountId(e.target.value);setAccountId("");}}><option value="">全部</option>{[...new Map((accountOptions.data??[]).filter(a=>a.bankAccountId).map(a=>[a.bankAccountId!,a.bankAccountName??a.name])).entries()].map(([id,name])=><option key={id} value={id}>{name}</option>)}</select></label>
        <label>账户<select aria-label="账户筛选" value={accountId} onChange={e => setAccountId(e.target.value)}><option value="">全部</option>{accountOptions.data?.filter(a=>!bankAccountId||a.bankAccountId===Number(bankAccountId)).map(item => <option key={item.id} value={item.id}>{accountLabel(item)}</option>)}</select></label>
        <label>成员<select aria-label="成员筛选" value={memberId} onChange={e => setMemberId(e.target.value)}><option value="">全部</option><option value="0">全体（家庭共同）</option>{members.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>分类<select aria-label="分类筛选" value={categoryId} onChange={e => setCategoryId(e.target.value)}><option value="">全部</option>{flatCategories.map(item => <option key={item.id} value={item.id}>{item.level === 2 ? '　' : ''}{item.name}</option>)}</select></label>
        <label className="search-field">搜索<input aria-label="搜索收支" value={q} onChange={e => setQ(e.target.value)} placeholder="商家、地点或备注" /></label>
        <a className="secondary-action" href={exportHref} download><Download size={15} aria-hidden="true"/>导出 CSV</a>
      </div>
      <QueryState loading={transactions.isLoading || accountOptions.isLoading || categoryOptions.isLoading || members.isLoading} error={transactions.error || accountOptions.error || categoryOptions.error || members.error} empty={transactions.data?.items.length === 0 && transactionPage === 0} emptyTitle="还没有收支记录" emptyDetail="点击“记一笔”开始记录家庭现金流。">
        <>
          <div className="responsive-data"><table><thead><tr><th>日期</th><th>类型</th><th>金额</th><th>分类</th><th>账户</th><th>成员</th><th>创建人</th><th>商家 / 备注</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{transactions.data?.items.map(item => <tr key={item.id}><td>{item.occurredOn}</td><td><StatusTag tone={item.kind === 'income' ? 'success' : 'blue'}>{item.kind === 'income' ? '收入' : item.principalAmount != null ? '还款现金流出' : '费用支出'}</StatusTag></td><td className={`money ${item.kind}`}>{item.kind === 'expense' ? '-' : '+'}{money(item.amount,item.currency)}{item.principalAmount != null && <small>本金 {money(item.principalAmount)} · 利息 {money(item.interestAmount)}</small>}</td><td>{item.categoryName}</td><td>{item.accountName}</td><td>{item.memberName}</td><td>{item.createdByName || '—'}</td><td><strong>{item.merchant || '—'}</strong><small>{item.note}</small></td><td><button className="text-action" onClick={() => setDetail(item)}>详情</button>{canEdit(item) && <button className="text-action" onClick={() => editTransaction(item)}>编辑</button>}{canDelete(item) && <button className="text-action danger" onClick={() => setDeleteId(item.id)}>删除</button>}</td></tr>)}</tbody></table>
            <div className="mobile-card-list">{transactions.data?.items.map(item => <article className="record-card" key={item.id}><header><div><StatusTag tone={item.kind === 'income' ? 'success' : 'blue'}>{item.kind === 'income' ? '收入' : item.principalAmount != null ? '还款现金流出' : '费用支出'}</StatusTag><strong>{item.categoryName}</strong></div><b>{item.kind === 'expense' ? '-' : '+'}{money(item.amount,item.currency)}</b></header><p>{item.occurredOn} · {item.accountName} · {item.memberName} · 创建人：{item.createdByName || '—'}</p><footer><span>{item.merchant || item.note || '无备注'}{item.principalAmount != null && <small>本金 {money(item.principalAmount)} · 利息 {money(item.interestAmount)}</small>}</span><span><button onClick={() => setDetail(item)}>详情</button>{canEdit(item) && <button onClick={() => editTransaction(item)}>编辑</button>}{canDelete(item) && <button onClick={() => setDeleteId(item.id)}>删除</button>}</span></footer></article>)}</div>
          </div>
          <PaginationControls page={transactionPage} totalPages={transactions.data?.totalPages ?? 0} hasNext={transactions.data?.hasNext ?? false} onPageChange={setTransactionPage} label="收支记录" />
        </>
      </QueryState>
    </>
    <ActionDialog open={section!=='transactions'} title={section==='accounts'?'账户管理':section==='categories'?'分类管理':section==='transfers'?'账户互转':'账务历史'} size="wide" obscured={!!accountDraft||!!categoryDraft||bankOverlay||transferOverlay||fxOverlay||bankRecords!==null||archiveCandidate!==null||categoryCandidate!==null} onClose={()=>setSection('transactions')}>
      {section==='accounts'&&<nav className="ledger-tools" aria-label="账户操作"><Button onClick={()=>setSection('transfers')}>账户互转</Button><Button onClick={()=>{setHistorySource(undefined);setSection('history');}}>账务历史</Button>{action&&<Button onClick={action.onClick}>{action.label}</Button>}</nav>}
      {section==='categories'&&action&&<Button theme="solid" type="primary" onClick={action.onClick}>{action.label}</Button>}
      {(section==='transfers'||section==='history')&&<Button theme="borderless" onClick={()=>setSection('accounts')}>返回账户</Button>}
    {section === 'transfers' && <TransfersPanel onOverlayChange={setTransferOverlay} request={request} role={role} accounts={accountOptions.data ?? []} onHistory={id => { setHistorySource({ sourceType: 'CASH_TRANSFER', sourceId: id }); setSection('history'); }} />}
    {section === 'transfers' && supportedCurrencies.length>1 && <FxTransfersPanel onOverlayChange={setFxOverlay} accountsReady={accountOptions.data!==undefined} request={request} role={role} accounts={accountOptions.data??[]} initialBankId={exchangeBankId} onInitialHandled={()=>setExchangeBankId(null)}/>}
    {section === 'history' && <AccountingHistory request={request} source={historySource} />}
    {section === 'accounts' && <><BankAccountsPanel request={request} manager={manager} currencies={supportedCurrencies} onHistory={setBankRecords} onOverlayChange={setBankOverlay} onExchange={id=>{setExchangeBankId(id);setSection('transfers');}} onOpening={item=>setAccountDraft({id:item.id,name:item.name,type:item.type,walletProvider:item.walletProvider??'',bankName:item.bankName??'',cardLastFour:item.cardLastFour??'',currency:item.currency,openingBalance:item.openingConfirmed?item.openingBalance:'',openingOn:item.openingOn??businessDate(),mode:'opening',confirmed:false,key:newIdempotencyKey()})}/><FormError error={archiveAccount.error}/><DataPanel title="家庭账户" meta="手工维护的账本账户，不连接支付平台或银行"><QueryState loading={accountOptions.isLoading} error={accountOptions.error} empty={!standalonePage?.items.length && accountPage === 0} emptyTitle="还没有账户"><><div className="compact-grid">{standalonePage?.items.map(item => <article className="data-card" key={item.id}><AccountIcon account={item}/><div><h3>{item.name}</h3><p>{accountDescription(item)}</p><p>账内可用余额 <strong>{item.openingConfirmed ? money(item.availableBalance,item.currency) : '待确认'}</strong></p><small>当前余额 {item.openingConfirmed ? money(item.balance,item.currency) : '待核对'} · 期初 {item.openingConfirmed ? money(item.openingBalance,item.currency) : '未确认'} · {item.openingOn ?? '起始日待确认'}</small></div>{manager && <div className="card-actions"><button onClick={() => setAccountDraft({ id: item.id, name: item.name, type: item.type, walletProvider: item.walletProvider ?? '', bankName: item.bankName ?? '', cardLastFour: item.cardLastFour ?? '', currency: item.currency, openingBalance: item.openingBalance, openingOn: item.openingOn ?? businessDate(), mode: 'metadata', confirmed: false, key: newIdempotencyKey() })}>编辑</button><button onClick={() => setAccountDraft({ id: item.id, name: item.name, type: item.type, walletProvider: item.walletProvider ?? '', bankName: item.bankName ?? '', cardLastFour: item.cardLastFour ?? '', currency: item.currency, openingBalance: item.openingConfirmed ? item.openingBalance : '0.00', openingOn: item.openingOn ?? businessDate(), mode: 'opening', confirmed: false, key: newIdempotencyKey() })}>{item.openingConfirmed ? '更正期初余额' : '确认期初余额'}</button><button onClick={() => setSection('transfers')}>账户互转</button><button disabled={!item.openingConfirmed} title={!item.openingConfirmed ? '请先确认期初余额和日期，再归档账户' : undefined} onClick={() => {archiveAccount.reset();setArchiveCandidate(item);}}>归档</button></div>}</article>)}</div><PaginationControls page={accountPage} totalPages={standalonePage?.totalPages ?? 0} hasNext={standalonePage?.hasNext ?? false} onPageChange={setAccountPage} label="家庭账户" /></></QueryState></DataPanel></>}
    {section === 'categories' && <><FormError error={deleteCategory.error}/><DataPanel title="收支分类" meta="最多两级，子分类必须与父分类同类型"><QueryState loading={categories.isLoading} error={categories.error} empty={!categories.data?.items.length && categoryPage === 0} emptyTitle="还没有分类"><><div className="category-tree">{orderedCategories.map(item => <article key={item.id}><div className="category-row"><i style={{ background: item.color }} /><strong>{item.name}</strong><StatusTag>{item.kind === 'income' ? '收入' : '费用支出'}</StatusTag>{manager && <span><button onClick={() => setCategoryDraft({ id: item.id, name: item.name, kind: item.kind, color: item.color, parentId: '' })}>编辑</button><button onClick={() => {deleteCategory.reset();setCategoryCandidate(item);}}>删除</button></span>}</div>{item.children?.map(child => <div className="category-row child" key={child.id}><i style={{ background: child.color }} />{child.name}{manager && <span><button onClick={() => setCategoryDraft({ id: child.id, name: child.name, kind: child.kind, color: child.color, parentId: String(item.id) })}>编辑</button><button onClick={() => {deleteCategory.reset();setCategoryCandidate(child);}}>删除</button></span>}</div>)}</article>)}</div><PaginationControls page={categoryPage} totalPages={categories.data?.totalPages ?? 0} hasNext={categories.data?.hasNext ?? false} onPageChange={setCategoryPage} label="分类" /></></QueryState></DataPanel></>}

    </ActionDialog>
    <ActionDialog draft={draft} sessionKey={draft?.id} busy={save.isPending} onSessionStart={save.reset} open={Boolean(draft)} title={draft?.id ? '编辑收支' : '记一笔'} onClose={() => setDraft(null)}>{draft && <TransactionForm draft={draft} request={request} accountsReady={accountOptions.data!==undefined} accounts={accountOptions.data ?? []} categories={flatCategories} members={members.data ?? []} error={save.error} saving={save.isPending} onChange={setDraft} onSubmit={() => save.mutate(draft)} />}</ActionDialog>
    <ActionDialog draft={accountDraft} sessionKey={accountDraft?.id} busy={saveAccount.isPending} onSessionStart={saveAccount.reset} open={Boolean(accountDraft)} title={accountDraft?.mode === 'opening' ? '确认或更正期初余额' : accountDraft?.id ? '编辑账户' : '新建账户'} onClose={() => setAccountDraft(null)}>{accountDraft && <form className="feature-form" onSubmit={e => { e.preventDefault(); saveAccount.mutate(accountDraft); }}><FormError error={saveAccount.error} /><fieldset disabled={accountDraft.mode === 'opening'}><label>账户名称<input name="name" required value={accountDraft.name} onChange={e => setAccountDraft({ ...accountDraft, name: e.target.value })} /></label><AccountTypePicker allowBank={accountDraft.mode!=="create"} value={accountDraft} onChange={classification => setAccountDraft({ ...accountDraft, ...classification, currency:!accountDraft.id&&['ALIPAY','WECHAT'].includes(classification.walletProvider)?'CNY':accountDraft.currency, bankName: classification.type === 'BANK' ? accountDraft.bankName : '', cardLastFour: classification.type === 'BANK' ? accountDraft.cardLastFour : '' })}/>{accountDraft.type === 'BANK' && <><label>银行名称（选填）<input name="bankName" maxLength={80} placeholder="例如：招商银行" value={accountDraft.bankName} onChange={e => setAccountDraft({ ...accountDraft, bankName: e.target.value })} /></label><label>银行卡尾号（选填）<input name="cardLastFour" inputMode="numeric" maxLength={4} pattern="[0-9]{4}" placeholder="仅四位尾号" value={accountDraft.cardLastFour} onChange={e => setAccountDraft({ ...accountDraft, cardLastFour: e.target.value })} /></label><p className="field-help">只需银行名称和四位尾号帮助区分，请勿填写完整卡号、密码或安全码。</p></>}<label>币种<select name="currency" disabled={Boolean(accountDraft.id)} value={accountDraft.currency} onChange={e=>setAccountDraft({...accountDraft,currency:e.target.value})}>{supportedCurrencies.filter(currency=>currency==='CNY'||!['ALIPAY','WECHAT'].includes(accountDraft.walletProvider)).map(currency=><option key={currency} value={currency}>{currency}</option>)}</select></label><p className="source-note">这里记录手工同步的账本余额，不会连接支付宝、微信或银行。编辑账户资料不会调整余额；实际资金移动请记收支或账户互转。</p></fieldset>{accountDraft.mode !== 'metadata' && <><p className="source-note">期初余额是账务起始日已经拥有的现金，不计作收入。更正会保留账务历史；零余额也需明确确认。</p><label>期初余额<input name="openingBalance" inputMode="decimal" value={accountDraft.openingBalance} onChange={e => setAccountDraft({ ...accountDraft, openingBalance: e.target.value, confirmed: false })} /></label><label>账务起始日期<DateField required name="openingOn" max={businessDate()} value={accountDraft.openingOn} onChange={e => setAccountDraft({ ...accountDraft, openingOn: e.target.value, confirmed: false })} /></label><label className="switch-line"><input type="checkbox" required checked={accountDraft.confirmed} onChange={e => setAccountDraft({ ...accountDraft, confirmed: e.target.checked })} />确认以上期初余额（包含零余额）和起始日期</label></>}<Button disabled={accountDraft.mode !== 'metadata' && !accountDraft.confirmed} htmlType="submit" theme="solid" type="primary" loading={saveAccount.isPending}>保存账户</Button></form>}</ActionDialog>
    <ActionDialog draft={categoryDraft} sessionKey={categoryDraft?.id} busy={saveCategory.isPending} onSessionStart={saveCategory.reset} open={Boolean(categoryDraft)} title={categoryDraft?.id ? '编辑分类' : '新建分类'} onClose={() => setCategoryDraft(null)}>{categoryDraft && <form className="feature-form" onSubmit={e => { e.preventDefault(); saveCategory.mutate(categoryDraft); }}><FormError error={saveCategory.error} /><label>分类名称<input name="name" required value={categoryDraft.name} onChange={e => setCategoryDraft({ ...categoryDraft, name: e.target.value })} /></label><label>收支类型<select name="kind" value={categoryDraft.kind} onChange={e => setCategoryDraft({ ...categoryDraft, kind: e.target.value as TransactionKind })}><option value="expense">支出</option><option value="income">收入</option></select></label><label>上级分类<select name="parentId" value={categoryDraft.parentId} onChange={e => setCategoryDraft({ ...categoryDraft, parentId: e.target.value })}><option value="">一级分类</option>{parentCategories.filter(item => item.kind === categoryDraft.kind && item.id !== categoryDraft.id).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>标记颜色<input name="color" type="color" value={categoryDraft.color} onChange={e => setCategoryDraft({ ...categoryDraft, color: e.target.value })} /></label><Button htmlType="submit" theme="solid" type="primary" loading={saveCategory.isPending}>保存分类</Button></form>}</ActionDialog>
    {detail&&<TransactionDetailsDialog item={detail} onClose={()=>setDetail(null)} onHistory={()=>{setHistorySource({sourceType:['MANUAL','RECURRING'].includes(detail.sourceType)?'TRANSACTION':detail.sourceType,sourceId:['MANUAL','RECURRING'].includes(detail.sourceType)?detail.id:detail.sourceId!});setDetail(null);setSection('history');}}/>}
    {bankRecords!==null&&<BankTransactionsDialog request={request} bankId={bankRecords} onClose={()=>setBankRecords(null)}/>}
    <ConfirmDialog open={archiveCandidate!==null} title="归档账户？" detail={<><p>{archiveCandidate?.name} · 需先清空余额并解除使用中的关联，历史流水保留。</p><FormError error={archiveAccount.error}/></>} loading={archiveAccount.isPending} confirmLabel="归档账户" onClose={()=>{if(!archiveAccount.isPending)setArchiveCandidate(null);}} onConfirm={()=>archiveCandidate&&archiveAccount.mutate(archiveCandidate.id)}/>
    <ConfirmDialog open={categoryCandidate!==null} title="删除分类？" detail={<><p>{categoryCandidate?.name} · 已被财务记录使用的分类不能删除。</p><FormError error={deleteCategory.error}/></>} loading={deleteCategory.isPending} danger confirmLabel="删除分类" onClose={()=>{if(!deleteCategory.isPending)setCategoryCandidate(null);}} onConfirm={()=>categoryCandidate&&deleteCategory.mutate(categoryCandidate.id)}/>
    <ConfirmDialog open={deleteId !== null} loading={remove.isPending} title="删除这笔收支？" detail={<><p>删除会冲回原入账并重新计算余额和费用。账务历史仍可追溯；原记录不能恢复。</p><FormError error={remove.error} /></>} danger confirmLabel="删除收支" onClose={() => { if (!remove.isPending) { setDeleteId(null); remove.reset(); } }} onConfirm={() => deleteId !== null && remove.mutate(deleteId)} />
  </PageScaffold>;
}

function TransactionForm({ request, accountsReady, draft, accounts, categories, members, error, saving, onChange, onSubmit }: { request:RequestFn; accountsReady:boolean; draft: TransactionDraft; accounts: Account[]; categories: Category[]; members: Member[]; error: unknown; saving: boolean; onChange: (draft: TransactionDraft) => void; onSubmit: () => void }) {
  const available = categories.filter(item => item.kind === draft.kind);
  const hitMonth = draft.occurredOn?.slice(0, 7);
  const amountValid = /^\d+(\.\d{1,2})?$/.test(draft.amount.trim()) && Number(draft.amount) > 0;
  // This endpoint previews an additional CNY expense, not a replacement or native FX amount.
  const canPreviewBudget = !draft.id && !draft.generated && accounts.find(account => String(account.id) === draft.accountId)?.currency === 'CNY';
  const hits = useQuery({ queryKey: ['budget-hit', hitMonth, draft.categoryId, draft.memberId, draft.amount], queryFn: () => request<BudgetHit[]>(`/api/budgets/hit-check?periodMonth=${hitMonth}&categoryId=${draft.categoryId}&memberId=${draft.memberId}&amountCents=${Math.round(Number(draft.amount) * 100)}`), enabled: canPreviewBudget && draft.kind === 'expense' && amountValid && Boolean(draft.categoryId) && Boolean(draft.memberId) && Boolean(draft.occurredOn) });
  const hitAlerts = canPreviewBudget && Array.isArray(hits.data) ? hits.data.filter(hit => hit.statusAfter !== 'ON_TRACK') : [];
  function submit(event: FormEvent) { event.preventDefault(); onSubmit(); }
  return <form className="feature-form" onSubmit={submit}><FormError error={error} />
    {draft.generated && <p className="source-note">此记录由业务生成，仅可修改商家、地点和备注。财务更正须回到原业务，已记录还款不可独立冲销。{draft.principalAmount != null && <>本金 {money(draft.principalAmount)} · 利息 {money(draft.interestAmount)}</>}</p>}
    <fieldset disabled={draft.generated}><div className="choice-row" role="radiogroup" aria-label="收支类型"><label><input name="kind" type="radio" checked={draft.kind === 'expense'} onChange={() => onChange({ ...draft, kind: 'expense', categoryId: '' })} />支出</label><label><input name="kind" type="radio" checked={draft.kind === 'income'} onChange={() => onChange({ ...draft, kind: 'income', categoryId: '' })} />收入</label></div>
    <label>金额<input name="amount" aria-label="金额" required inputMode="decimal" value={draft.amount} onChange={e => onChange({ ...draft, amount: e.target.value })} placeholder="0.00" /></label>
    <label>日期<DateField name="occurredOn" aria-label="日期" required max={businessDate()} value={draft.occurredOn} onChange={e => onChange({ ...draft, occurredOn: e.target.value })} /></label>
    <BankAccountPicker accountsReady={accountsReady} request={request} accounts={accounts} label="账户" name="accountId" value={draft.accountId} onChange={id=>onChange({...draft,accountId:id})}/>
    <label>分类<select name="categoryId" aria-label="分类" required value={draft.categoryId} onChange={e => onChange({ ...draft, categoryId: e.target.value })}><option value="">请选择分类</option>{available.map(item => <option key={item.id} value={item.id}>{item.level === 2 ? '　' : ''}{item.name}</option>)}</select></label>
    <label>成员<select name="memberId" aria-label="成员" required value={draft.memberId} onChange={e => onChange({ ...draft, memberId: e.target.value })}><option value="">请选择成员</option><option value="0">全体（家庭共同）</option>{members.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
    </fieldset>{!draft.generated && <PaymentPreview account={accounts.find(item => String(item.id) === draft.accountId)} amount={draft.amount} incoming={draft.kind === 'income'} adjustment={Boolean(draft.id)} />}
    {!draft.generated && draft.kind === 'expense' && hitAlerts.length > 0 && <section className="budget-hit-note" role="status" aria-label="预算影响提醒">{hitAlerts.map(hit => { const label = [hit.categoryName, hit.memberName].filter(Boolean).join(' · '); const tone = hit.statusAfter === 'OVER_BUDGET' || hit.statusAfter === 'AT_LIMIT' ? 'budget-hit-over' : ''; return <p key={hit.budgetId} className={tone}>此笔将使“{label}”{hit.scopeType === 'CATEGORY' ? '预算' : '观察线'}使用至 {hit.percentAfter}%（{hit.statusAfter === 'OVER_BUDGET' ? '将超支' : hit.statusAfter === 'AT_LIMIT' ? '将用尽' : '接近额度'}；当前已用 {money(hit.spent)}）</p>; })}</section>}
    <label>商家<input name="merchant" aria-label="商家" value={draft.merchant} onChange={e => onChange({ ...draft, merchant: e.target.value })} /></label><label>地点<input name="location" aria-label="地点" value={draft.location} onChange={e => onChange({ ...draft, location: e.target.value })} /></label><label>备注<textarea name="note" aria-label="备注" value={draft.note} onChange={e => onChange({ ...draft, note: e.target.value })} /></label>
    <Button htmlType="submit" theme="solid" type="primary" loading={saving}>保存收支</Button>
  </form>;
}
