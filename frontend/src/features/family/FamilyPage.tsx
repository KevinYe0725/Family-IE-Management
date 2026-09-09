import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import type { CreatedInvite, Family, FamilyInvite, FamilyPerson, HouseholdRole, Membership, Page } from '../../api/contracts';
import { FamilyDirectory } from './FamilyDirectory';
import { PaginationControls, usePageRecovery } from '../../shared/pagination';
import { ConfirmDialog, DataPanel, Drawer, FormError, PageScaffold, QueryState, StatusTag, dateText, isManager, type RequestFn } from '../common';

export function FamilyPage({ request, role, householdName, inviteRequested = false, onInviteRequestHandled }: { request: RequestFn; role: HouseholdRole; householdName?: string; inviteRequested?: boolean; onInviteRequestHandled?: () => void }) {
  const family = useQuery({ queryKey: ['family'], queryFn: () => request<Family>('/api/family') });
  const [invitePage, setInvitePage] = useState(0);
  const people = useQuery({ queryKey: ['family-people'], queryFn: () => request<FamilyPerson[]>('/api/family/people') });
  const invites = useQuery({ queryKey: ['family-invites', 'page', invitePage], queryFn: () => request<Page<FamilyInvite>>(`/api/family/invites?page=${invitePage}&size=50`, { responseType: 'page' }), enabled: isManager(role) });
  usePageRecovery(invitePage, invites.data, setInvitePage);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteRole, setInviteRole] = useState<'MEMBER' | 'ADMIN'>('MEMBER');
  const [maxUses, setMaxUses] = useState('5');
  const [createdInvite, setCreatedInvite] = useState<CreatedInvite | null>(null);
  useEffect(() => {
    if (!inviteRequested) return;
    if (isManager(role)) { setInviteOpen(true); setCreatedInvite(null); }
    onInviteRequestHandled?.();
  }, [inviteRequested, role, onInviteRequestHandled]);
  const [transferId, setTransferId] = useState<number | null>(null);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [rename, setRename] = useState<string | null>(null);
  const [password, setPassword] = useState<{ currentPassword: string; newPassword: string } | null>(null);
  const createInvite = useMutation({ mutationFn: () => request<CreatedInvite>('/api/family/invites', { method: 'POST', body: { role: inviteRole, maxUses: Number(maxUses) } }), onSuccess: value => { setCreatedInvite(value); } });
  const revoke = useMutation({ mutationFn: (id: number) => request<void>(`/api/family/invites/${id}`, { method: 'DELETE' }) });
  const changeRole = useMutation({ mutationFn: ({ id, nextRole }: { id: number; nextRole: 'ADMIN' | 'MEMBER' }) => request<Membership>(`/api/family/memberships/${id}`, { method: 'PATCH', body: { role: nextRole } }) });
  const transfer = useMutation({ mutationFn: (membershipId: number) => request<void>('/api/family/transfer-ownership', { method: 'POST', body: { membershipId } }), onSuccess: () => { setTransferId(null); } });
  const saveRename = useMutation({ mutationFn: (name: string) => request<Family>('/api/family', { method: 'PATCH', body: { name } }), onSuccess: () => { setRename(null); } });
  const changePassword = useMutation({ mutationFn: (value: NonNullable<typeof password>) => request<void>('/api/auth/change-password', { method: 'POST', body: value }), onSuccess: () => setPassword(null) });
  const archive = useMutation({ mutationFn: () => request<void>('/api/family', { method: 'DELETE', body: { confirmName } }), onSuccess: () => window.location.assign('/login') });
  const currentName = family.data?.name ?? householdName ?? '';
  const manager = isManager(role);
  return <PageScaffold title="家庭与成员" primaryAction={manager ? { label: '邀请成员', onClick: () => { setInviteOpen(true); setCreatedInvite(null); } } : undefined}>
    <div className="family-banner"><span className="family-seal">家</span><div><p>当前家庭</p><h2>{currentName || '读取中'}</h2><span>{people.data ? `${people.data.length} 位家人 · ${people.data.filter(person => person.loginStatus === 'AVAILABLE').length} 人可登录` : '正在读取家人'}</span></div>{role === 'OWNER' && <Button size="small" onClick={() => setRename(currentName)}>修改名称</Button>}</div>
    <div className="two-column-layout family-layout"><FamilyDirectory people={people.data ?? []} loading={people.isLoading} error={people.error} role={role} busy={changeRole.isPending || transfer.isPending} actionError={changeRole.error ?? transfer.error} onRoleChange={(id, nextRole) => changeRole.mutate({ id, nextRole })} onTransfer={setTransferId} />
      <div className="stacked-panels">{manager && <DataPanel title="邀请记录" meta="邀请原文只在创建成功时展示一次"><QueryState loading={invites.isLoading} error={invites.error} empty={!invites.data?.items.length && invitePage === 0} emptyTitle="还没有邀请"><><div className="invite-list">{invites.data?.items.map(item => <article key={item.id}><div><strong>{item.role === 'ADMIN' ? '管理员邀请' : '成员邀请'}</strong><p>已用 {item.usedCount}/{item.maxUses} · 到期 {dateText(item.expiresAt)}</p></div>{item.revokedAt ? <StatusTag>已撤销</StatusTag> : <button onClick={() => revoke.mutate(item.id)}>撤销</button>}</article>)}</div><PaginationControls page={invitePage} totalPages={invites.data?.totalPages ?? 0} hasNext={invites.data?.hasNext ?? false} onPageChange={setInvitePage} label="邀请记录" /></></QueryState></DataPanel>}<DataPanel title="账号与安全"><div className="settings-actions"><button aria-label="修改登录密码" onClick={() => setPassword({ currentPassword: '', newPassword: '' })}><strong>修改登录密码</strong><span>更新后继续使用当前会话</span></button>{role === 'OWNER' && <button aria-label="归档家庭" className="danger-zone" onClick={() => setArchiveOpen(true)}><strong>归档家庭</strong><span>停止成员登录，保留历史财务数据</span></button>}</div></DataPanel></div></div>
    <Drawer draft={createdInvite ? null : { inviteRole, maxUses }} busy={createInvite.isPending} onSessionStart={createInvite.reset} open={inviteOpen} title="邀请成员" description="邀请码只在本次成功页面显示，请通过可信方式发送。" onClose={() => { setInviteOpen(false); setCreatedInvite(null); setInviteRole('MEMBER'); setMaxUses('5'); }}>{createdInvite ? <div className="invite-success"><StatusTag tone="success">邀请已创建</StatusTag><label>一次性邀请码<input readOnly value={createdInvite.token} /></label><Button theme="solid" type="primary" onClick={() => navigator.clipboard.writeText(createdInvite.token)}>复制邀请码</Button><p>{createdInvite.role === 'ADMIN' ? '管理员' : '成员'} · 最多 {createdInvite.maxUses} 次 · {dateText(createdInvite.expiresAt)} 到期</p></div> : <form className="feature-form" onSubmit={e => { e.preventDefault(); createInvite.mutate(); }}><FormError error={createInvite.error} /><label>邀请角色<select name="role" value={inviteRole} onChange={e => setInviteRole(e.target.value as 'MEMBER' | 'ADMIN')}><option value="MEMBER">家庭成员</option>{role === 'OWNER' && <option value="ADMIN">家庭管理员</option>}</select></label><label>最多使用次数<input type="number" min="1" max="100" name="maxUses" value={maxUses} onChange={e => setMaxUses(e.target.value)} /></label><Button htmlType="submit" theme="solid" type="primary" loading={createInvite.isPending}>创建邀请</Button></form>}</Drawer>
    <Drawer draft={rename} busy={saveRename.isPending} onSessionStart={saveRename.reset} open={rename !== null} title="修改家庭名称" onClose={() => setRename(null)}>{rename !== null && <form className="feature-form" onSubmit={e => { e.preventDefault(); saveRename.mutate(rename); }}><FormError error={saveRename.error} /><label>家庭名称<input required name="name" value={rename} onChange={e => setRename(e.target.value)} /></label><Button htmlType="submit" theme="solid" type="primary">保存名称</Button></form>}</Drawer>
    <Drawer draft={password} busy={changePassword.isPending} onSessionStart={changePassword.reset} open={password !== null} title="修改登录密码" onClose={() => setPassword(null)}>{password && <form className="feature-form" onSubmit={(e: FormEvent) => { e.preventDefault(); changePassword.mutate(password); }}><FormError error={changePassword.error} /><label>当前密码<input name="currentPassword" type="password" required value={password.currentPassword} onChange={e => setPassword({ ...password, currentPassword: e.target.value })} /></label><label>新密码<input name="newPassword" type="password" minLength={8} maxLength={72} required value={password.newPassword} onChange={e => setPassword({ ...password, newPassword: e.target.value })} /></label><Button htmlType="submit" theme="solid" type="primary">更新密码</Button></form>}</Drawer>
    <ConfirmDialog open={transferId !== null} title="转让家庭所有权？" detail="转让后，你会继承对方原有角色；只有新所有者可以再次转让或归档家庭。" confirmLabel="确认转让" onClose={() => setTransferId(null)} onConfirm={() => transferId !== null && transfer.mutate(transferId)} />
    {archiveOpen && <div className="sheet-backdrop dialog-backdrop"><section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="archive-title"><h2 id="archive-title">归档家庭</h2><p>成员将无法继续登录，但历史财务数据与备份不会被物理删除。</p><FormError error={archive.error} /><label>输入“{currentName}”确认<input value={confirmName} onChange={e => setConfirmName(e.target.value)} /></label><footer><Button onClick={() => setArchiveOpen(false)}>取消</Button><Button theme="solid" type="danger" disabled={confirmName !== currentName} loading={archive.isPending} onClick={() => archive.mutate()}>归档家庭</Button></footer></section></div>}
  </PageScaffold>;
}
