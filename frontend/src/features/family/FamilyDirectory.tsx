import { useEffect, useState } from 'react';
import type { FamilyPerson, HouseholdRole } from '../../api/contracts';
import { PaginationControls } from '../../shared/pagination';
import { DataPanel, FormError, QueryState, StatusTag } from '../common';

export function FamilyDirectory({ people, loading, error, role, busy, actionError, onRoleChange, onTransfer }: {
  people: FamilyPerson[]; loading: boolean; error: unknown; role: HouseholdRole; busy: boolean; actionError: unknown;
  onRoleChange: (id: number, role: 'ADMIN' | 'MEMBER') => void; onTransfer: (id: number) => void;
}) {
  const [page, setPage] = useState(0);
  const pages = Math.ceil(people.length / 20);
  useEffect(() => { if (page >= pages && page > 0) setPage(Math.max(0, pages - 1)); }, [page, pages]);
  return <DataPanel title="家人">
    <FormError error={actionError} />
    <QueryState loading={loading} error={error} empty={!people.length} emptyTitle="还没有家人">
      <div className="member-list family-directory">{people.slice(page * 20, (page + 1) * 20).map(person => {
        const available = person.loginStatus === 'AVAILABLE';
        const permission = person.role === 'OWNER' ? '所有者' : person.role === 'ADMIN' ? '管理员' : '普通成员';
        return <article key={person.memberId != null ? `person-${person.memberId}` : `login-${person.membershipId}`}>
          <span className="user-avatar" aria-hidden="true">{person.name.slice(0, 1)}</span>
          <div className="family-person-identity">
            <h3>{person.name}</h3>
            {person.relationship && <p>{person.relationship}</p>}
            {person.accountDisplayName && person.accountDisplayName !== person.name && <p>账号昵称：{person.accountDisplayName}</p>}
            {person.email && <p>{person.email}</p>}
          </div>
          <div className="family-person-access">
            <StatusTag tone={available ? 'success' : 'neutral'}>{available ? '可登录' : person.loginStatus === 'SUSPENDED' ? '登录已停用' : '由家人代记'}</StatusTag>
            {person.membershipId != null && <span className="family-person-permission">权限：{permission}</span>}
          </div>
          {role === 'OWNER' && available && person.membershipId != null && person.role !== 'OWNER' && <div className="card-actions">
            <button disabled={busy} onClick={() => onRoleChange(person.membershipId!, person.role === 'ADMIN' ? 'MEMBER' : 'ADMIN')}>{person.role === 'ADMIN' ? '设为成员' : '设为管理员'}</button>
            <button disabled={busy} onClick={() => onTransfer(person.membershipId!)}>转让所有权</button>
          </div>}
        </article>;
      })}</div>
      <PaginationControls page={page} totalPages={pages} hasNext={page + 1 < pages} onPageChange={setPage} label="家人" />
    </QueryState>
  </DataPanel>;
}
