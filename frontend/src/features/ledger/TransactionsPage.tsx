import { useMemo, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { Account, AccountType, Category, HouseholdRole, Member, Transaction, TransactionKind } from '../../api/contracts';
import { localYearMonth } from '../../shared/runtime';
import { ConfirmDialog, DataPanel, Drawer, FormError, PageScaffold, QueryState, StatusTag, isManager, money, type RequestFn } from '../common';

interface TransactionDraft { id?: number; kind: TransactionKind; amount: string; occurredOn: string; accountId: string; memberId: string; categoryId: string; merchant: string; location: string; note: string }
const emptyDraft = (): TransactionDraft => ({ kind: 'expense', amount: '', occurredOn: new Date().toISOString().slice(0, 10), accountId: '', memberId: '', categoryId: '', merchant: '', location: '', note: '' });

export function TransactionsPage({ request, role }: { request: RequestFn; role: HouseholdRole; userId: number }) {
  const queryClient = useQueryClient();
  const [section, setSection] = useState<'transactions' | 'accounts' | 'categories'>('transactions');
  const [month, setMonth] = useState(localYearMonth());
  const [kind, setKind] = useState('');
  const [q, setQ] = useState('');
  const [accountId, setAccountId] = useState('');
  const [memberId, setMemberId] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [draft, setDraft] = useState<TransactionDraft | null>(null);
  const [deleteId, setDeleteId] = useState<number | null>(null);
  const [accountDraft, setAccountDraft] = useState<{ id?: number; name: string; type: AccountType; currency: string; openingBalance: string } | null>(null);
  const [categoryDraft, setCategoryDraft] = useState<{ id?: number; name: string; kind: TransactionKind; color: string; parentId: string } | null>(null);

  const filters = useMemo(() => new URLSearchParams(Object.entries({ month, kind, q, accountId, memberId, categoryId }).filter(([, value]) => value)).toString(), [month, kind, q, accountId, memberId, categoryId]);
  const transactions = useQuery({ queryKey: ['transactions', filters], queryFn: () => request<Transaction[]>(`/api/transactions?${filters}&page=0&size=50`) });
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => request<{ items: Account[] }>('/api/accounts?page=0&size=50') });
  const categories = useQuery({ queryKey: ['categories'], queryFn: () => request<Category[]>('/api/categories?projection=tree&page=0&size=100') });
  const members = useQuery({ queryKey: ['members'], queryFn: () => request<Member[]>('/api/members') });
  const flatCategories = useMemo(() => (categories.data ?? []).flatMap(item => [item, ...(item.children ?? [])]), [categories.data]);

  const save = useMutation({
    mutationFn: (value: TransactionDraft) => request<Transaction>(value.id ? `/api/transactions/${value.id}` : '/api/transactions', {
      method: value.id ? 'PATCH' : 'POST',
      body: { kind: value.kind, amount: value.amount, occurredOn: value.occurredOn, accountId: Number(value.accountId), memberId: Number(value.memberId), categoryId: Number(value.categoryId), merchant: value.merchant || null, location: value.location || null, note: value.note || null }
    }),
    onSuccess: async () => { setDraft(null); await Promise.all([queryClient.invalidateQueries({ queryKey: ['transactions'] }), queryClient.invalidateQueries({ queryKey: ['dashboard'] }), queryClient.invalidateQueries({ queryKey: ['budget-usage'] })]); }
  });
  const remove = useMutation({ mutationFn: (id: number) => request<void>(`/api/transactions/${id}`, { method: 'DELETE' }), onSuccess: async () => { setDeleteId(null); await queryClient.invalidateQueries({ queryKey: ['transactions'] }); } });
  const saveAccount = useMutation({ mutationFn: (value: NonNullable<typeof accountDraft>) => request<Account>(value.id ? `/api/accounts/${value.id}` : '/api/accounts', { method: value.id ? 'PATCH' : 'POST', body: value }), onSuccess: async () => { setAccountDraft(null); await queryClient.invalidateQueries({ queryKey: ['accounts'] }); } });
  const archiveAccount = useMutation({ mutationFn: (id: number) => request<void>(`/api/accounts/${id}`, { method: 'DELETE' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['accounts'] }) });
  const saveCategory = useMutation({ mutationFn: (value: NonNullable<typeof categoryDraft>) => request<Category>(value.id ? `/api/categories/${value.id}` : '/api/categories', { method: value.id ? 'PATCH' : 'POST', body: { name: value.name, kind: value.kind, color: value.color, parentId: value.parentId ? Number(value.parentId) : null } }), onSuccess: async () => { setCategoryDraft(null); await queryClient.invalidateQueries({ queryKey: ['categories'] }); } });
  const deleteCategory = useMutation({ mutationFn: (id: number) => request<void>(`/api/categories/${id}`, { method: 'DELETE' }), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['categories'] }) });

  function editTransaction(item: Transaction) { setDraft({ id: item.id, kind: item.kind, amount: item.amount, occurredOn: item.occurredOn, accountId: String(item.accountId), memberId: String(item.memberId), categoryId: String(item.categoryId), merchant: item.merchant ?? '', location: item.location ?? '', note: item.note ?? '' }); }
  const manager = isManager(role);
  const action = section === 'transactions' ? { label: '记一笔', onClick: () => setDraft(emptyDraft()) } : section === 'accounts' && manager ? { label: '新建账户', onClick: () => setAccountDraft({ name: '', type: 'BANK', currency: 'CNY', openingBalance: '0.00' }) } : section === 'categories' && manager ? { label: '新建分类', onClick: () => setCategoryDraft({ name: '', kind: 'expense', color: '#3370FF', parentId: '' }) } : undefined;

  return <PageScaffold title="收支明细" description="账户、分类和每一笔家庭现金流，都以服务器记录为准。" primaryAction={action}>
    <nav className="segmented-tabs" aria-label="账本模块"><button className={section === 'transactions' ? 'active' : ''} onClick={() => setSection('transactions')}>收支</button><button className={section === 'accounts' ? 'active' : ''} onClick={() => setSection('accounts')}>账户</button><button className={section === 'categories' ? 'active' : ''} onClick={() => setSection('categories')}>分类</button></nav>
    {section === 'transactions' && <>
      <div className="filter-bar">
        <label>账期<input aria-label="账期" type="month" value={month} onChange={e => setMonth(e.target.value)} /></label>
        <label>类型<select aria-label="收支类型筛选" value={kind} onChange={e => setKind(e.target.value)}><option value="">全部</option><option value="expense">支出</option><option value="income">收入</option></select></label>
        <label>账户<select aria-label="账户筛选" value={accountId} onChange={e => setAccountId(e.target.value)}><option value="">全部</option>{accounts.data?.items.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>成员<select aria-label="成员筛选" value={memberId} onChange={e => setMemberId(e.target.value)}><option value="">全部</option>{members.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>分类<select aria-label="分类筛选" value={categoryId} onChange={e => setCategoryId(e.target.value)}><option value="">全部</option>{flatCategories.map(item => <option key={item.id} value={item.id}>{item.level === 2 ? '　' : ''}{item.name}</option>)}</select></label>
        <label className="search-field">搜索<input aria-label="搜索收支" value={q} onChange={e => setQ(e.target.value)} placeholder="商家、地点或备注" /></label>
      </div>
      <QueryState loading={transactions.isLoading || accounts.isLoading || categories.isLoading || members.isLoading} error={transactions.error || accounts.error || categories.error || members.error} empty={transactions.data?.length === 0} emptyTitle="还没有收支记录" emptyDetail="点击“记一笔”开始记录家庭现金流。">
        <div className="responsive-data"><table><thead><tr><th>日期</th><th>类型</th><th>金额</th><th>分类</th><th>账户</th><th>成员</th><th>商家 / 备注</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{transactions.data?.map(item => <tr key={item.id}><td>{item.occurredOn}</td><td><StatusTag tone={item.kind === 'income' ? 'success' : 'blue'}>{item.kind === 'income' ? '收入' : '支出'}</StatusTag></td><td className={`money ${item.kind}`}>{item.kind === 'expense' ? '-' : '+'}{money(item.amount)}</td><td>{item.categoryName}</td><td>{item.accountName}</td><td>{item.memberName}</td><td><strong>{item.merchant || '—'}</strong><small>{item.note}</small></td><td><button className="text-action" onClick={() => editTransaction(item)}>编辑</button><button className="text-action danger" onClick={() => setDeleteId(item.id)}>删除</button></td></tr>)}</tbody></table>
          <div className="mobile-card-list">{transactions.data?.map(item => <article className="record-card" key={item.id}><header><div><StatusTag tone={item.kind === 'income' ? 'success' : 'blue'}>{item.kind === 'income' ? '收入' : '支出'}</StatusTag><strong>{item.categoryName}</strong></div><b>{item.kind === 'expense' ? '-' : '+'}{money(item.amount)}</b></header><p>{item.occurredOn} · {item.accountName} · {item.memberName}</p><footer><span>{item.merchant || item.note || '无备注'}</span><span><button onClick={() => editTransaction(item)}>编辑</button><button onClick={() => setDeleteId(item.id)}>删除</button></span></footer></article>)}</div>
        </div>
      </QueryState>
    </>}
    {section === 'accounts' && <DataPanel title="家庭账户" meta="现金、银行卡和钱包账户"><QueryState loading={accounts.isLoading} error={accounts.error} empty={!accounts.data?.items.length} emptyTitle="还没有账户"><div className="compact-grid">{accounts.data?.items.map(item => <article className="data-card" key={item.id}><span className="card-icon">{item.type === 'CASH' ? '现' : item.type === 'BANK' ? '卡' : '包'}</span><div><h3>{item.name}</h3><p>{item.currency} · 期初 {money(item.openingBalance)}</p></div>{manager && <div className="card-actions"><button onClick={() => setAccountDraft({ id: item.id, name: item.name, type: item.type, currency: item.currency, openingBalance: item.openingBalance })}>编辑</button><button onClick={() => archiveAccount.mutate(item.id)}>归档</button></div>}</article>)}</div></QueryState></DataPanel>}
    {section === 'categories' && <DataPanel title="收支分类" meta="最多两级，子分类必须与父分类同类型"><QueryState loading={categories.isLoading} error={categories.error} empty={!categories.data?.length} emptyTitle="还没有分类"><div className="category-tree">{categories.data?.map(item => <article key={item.id}><div className="category-row"><i style={{ background: item.color }} /><strong>{item.name}</strong><StatusTag>{item.kind === 'income' ? '收入' : '支出'}</StatusTag>{manager && <span><button onClick={() => setCategoryDraft({ id: item.id, name: item.name, kind: item.kind, color: item.color, parentId: '' })}>编辑</button><button onClick={() => deleteCategory.mutate(item.id)}>删除</button></span>}</div>{item.children?.map(child => <div className="category-row child" key={child.id}><i style={{ background: child.color }} />{child.name}{manager && <span><button onClick={() => setCategoryDraft({ id: child.id, name: child.name, kind: child.kind, color: child.color, parentId: String(item.id) })}>编辑</button><button onClick={() => deleteCategory.mutate(child.id)}>删除</button></span>}</div>)}</article>)}</div></QueryState></DataPanel>}

    <Drawer open={Boolean(draft)} title={draft?.id ? '编辑收支' : '记一笔'} description="保存后，预算与总览会从服务器重新读取。" onClose={() => setDraft(null)}>{draft && <TransactionForm draft={draft} accounts={accounts.data?.items ?? []} categories={flatCategories} members={members.data ?? []} error={save.error} saving={save.isPending} onChange={setDraft} onSubmit={() => save.mutate(draft)} />}</Drawer>
    <Drawer open={Boolean(accountDraft)} title={accountDraft?.id ? '编辑账户' : '新建账户'} onClose={() => setAccountDraft(null)}>{accountDraft && <form className="feature-form" onSubmit={e => { e.preventDefault(); saveAccount.mutate(accountDraft); }}><FormError error={saveAccount.error} /><label>账户名称<input required value={accountDraft.name} onChange={e => setAccountDraft({ ...accountDraft, name: e.target.value })} /></label><label>账户类型<select value={accountDraft.type} onChange={e => setAccountDraft({ ...accountDraft, type: e.target.value as AccountType })}><option value="CASH">现金</option><option value="BANK">银行卡</option><option value="WALLET">电子钱包</option></select></label><label>币种<input value={accountDraft.currency} onChange={e => setAccountDraft({ ...accountDraft, currency: e.target.value })} /></label><label>期初余额<input inputMode="decimal" value={accountDraft.openingBalance} onChange={e => setAccountDraft({ ...accountDraft, openingBalance: e.target.value })} /></label><Button htmlType="submit" theme="solid" type="primary" loading={saveAccount.isPending}>保存账户</Button></form>}</Drawer>
    <Drawer open={Boolean(categoryDraft)} title={categoryDraft?.id ? '编辑分类' : '新建分类'} onClose={() => setCategoryDraft(null)}>{categoryDraft && <form className="feature-form" onSubmit={e => { e.preventDefault(); saveCategory.mutate(categoryDraft); }}><FormError error={saveCategory.error} /><label>分类名称<input required value={categoryDraft.name} onChange={e => setCategoryDraft({ ...categoryDraft, name: e.target.value })} /></label><label>收支类型<select value={categoryDraft.kind} onChange={e => setCategoryDraft({ ...categoryDraft, kind: e.target.value as TransactionKind })}><option value="expense">支出</option><option value="income">收入</option></select></label><label>上级分类<select value={categoryDraft.parentId} onChange={e => setCategoryDraft({ ...categoryDraft, parentId: e.target.value })}><option value="">一级分类</option>{categories.data?.filter(item => item.kind === categoryDraft.kind && item.id !== categoryDraft.id).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>标记颜色<input type="color" value={categoryDraft.color} onChange={e => setCategoryDraft({ ...categoryDraft, color: e.target.value })} /></label><Button htmlType="submit" theme="solid" type="primary" loading={saveCategory.isPending}>保存分类</Button></form>}</Drawer>
    <ConfirmDialog open={deleteId !== null} title="删除这笔收支？" detail="该操作会重新计算相关预算和总览，且无法撤销。" danger confirmLabel="删除收支" onClose={() => setDeleteId(null)} onConfirm={() => deleteId !== null && remove.mutate(deleteId)} />
  </PageScaffold>;
}

function TransactionForm({ draft, accounts, categories, members, error, saving, onChange, onSubmit }: { draft: TransactionDraft; accounts: Account[]; categories: Category[]; members: Member[]; error: unknown; saving: boolean; onChange: (draft: TransactionDraft) => void; onSubmit: () => void }) {
  const available = categories.filter(item => item.kind === draft.kind);
  function submit(event: FormEvent) { event.preventDefault(); onSubmit(); }
  return <form className="feature-form" onSubmit={submit}><FormError error={error} />
    <div className="choice-row" role="radiogroup" aria-label="收支类型"><label><input type="radio" checked={draft.kind === 'expense'} onChange={() => onChange({ ...draft, kind: 'expense', categoryId: '' })} />支出</label><label><input type="radio" checked={draft.kind === 'income'} onChange={() => onChange({ ...draft, kind: 'income', categoryId: '' })} />收入</label></div>
    <label>金额<input aria-label="金额" required inputMode="decimal" value={draft.amount} onChange={e => onChange({ ...draft, amount: e.target.value })} placeholder="0.00" /></label>
    <label>日期<input aria-label="日期" required type="date" value={draft.occurredOn} onChange={e => onChange({ ...draft, occurredOn: e.target.value })} /></label>
    <label>账户<select aria-label="账户" required value={draft.accountId} onChange={e => onChange({ ...draft, accountId: e.target.value })}><option value="">请选择账户</option>{accounts.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
    <label>分类<select aria-label="分类" required value={draft.categoryId} onChange={e => onChange({ ...draft, categoryId: e.target.value })}><option value="">请选择分类</option>{available.map(item => <option key={item.id} value={item.id}>{item.level === 2 ? '　' : ''}{item.name}</option>)}</select></label>
    <label>成员<select aria-label="成员" required value={draft.memberId} onChange={e => onChange({ ...draft, memberId: e.target.value })}><option value="">请选择成员</option>{members.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
    <label>商家<input aria-label="商家" value={draft.merchant} onChange={e => onChange({ ...draft, merchant: e.target.value })} /></label><label>地点<input aria-label="地点" value={draft.location} onChange={e => onChange({ ...draft, location: e.target.value })} /></label><label>备注<textarea aria-label="备注" value={draft.note} onChange={e => onChange({ ...draft, note: e.target.value })} /></label>
    <Button htmlType="submit" theme="solid" type="primary" loading={saving}>保存收支</Button>
  </form>;
}
