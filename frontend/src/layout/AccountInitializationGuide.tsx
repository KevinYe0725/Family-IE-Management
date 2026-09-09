import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import type { Account, Page, Session } from '../api/contracts';
import type { ApiRequest } from '../api/client';
import { ConfirmDialog, isManager } from '../features/common';
import { readAllPages } from '../shared/pagination';
import { AccountIdentity } from '../features/ledger/AccountIdentity';

/** One reminder per authenticated workspace entry; no financial writes or persisted dismissal. */
export function AccountInitializationGuide({ session, request, onDecisionChange }: {
  session: Session;
  request: ApiRequest;
  onDecisionChange?: (decision: 'waiting' | 'show' | 'dismissed') => void;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const onAccountPage = location.pathname === '/workspace/transactions'
    && new URLSearchParams(location.search).get('section') === 'accounts';
  const [decision, setDecision] = useState<'waiting' | 'show' | 'dismissed'>(() => onAccountPage ? 'dismissed' : 'waiting');
  const accounts = useQuery({
    queryKey: ['accounts', 'initialization-guide', session.userId, session.householdId],
    queryFn: ({ signal }) => readAllPages(page => {
      signal.throwIfAborted();
      return request<Page<Account>>(`/api/accounts?page=${page}&size=50`, { responseType: 'page', signal });
    }),
    enabled: decision !== 'dismissed',
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false
  });
  const pending = useMemo(() => (accounts.data ?? []).filter(account => !account.archivedAt
    && (!account.openingConfirmed || !account.openingOn)), [accounts.data]);

  useEffect(() => {
    if (decision === 'dismissed') return;
    if (onAccountPage || accounts.isError) { setDecision('dismissed'); return; }
    if (accounts.isFetching || !accounts.data) return;
    if (!pending.length) { setDecision('dismissed'); return; }
    if (decision === 'waiting') {
      // A slow login check must not cover an editor or an unresolved repayment dialog.
      setDecision(document.querySelector('[role="dialog"]') ? 'dismissed' : 'show');
    }
  }, [decision, onAccountPage, accounts.isError, accounts.isFetching, accounts.data, pending.length]);

  const manager = isManager(session.role);
  const close = () => setDecision('dismissed');
  useEffect(() => { onDecisionChange?.(decision); }, [decision, onDecisionChange]);
  return <ConfirmDialog
    open={decision === 'show' && !accounts.isFetching && !accounts.isError && pending.length > 0 && !onAccountPage}
    title="先确认账户期初余额"
    detail={<div className="account-setup-guide">
      <p>当前家庭还有 <strong>{pending.length}</strong> 个现金账户未完成初始化。</p>
      <ul aria-label="待初始化账户">{pending.slice(0, 3).map(account => <li key={account.id}><AccountIdentity account={account}/></li>)}</ul>
      {pending.length > 3 && <p>其余 {pending.length - 3} 个账户可在账户页查看。</p>}
      <p>确认期初余额和账务起始日期后，才能准确记账和还款。零余额也需要确认，不会自动填写或扣款。</p>
      {!manager && <p>请联系家庭所有者或管理员完成初始化，你可以先查看家庭成员。</p>}
    </div>}
    cancelLabel="稍后处理"
    confirmLabel={manager ? '去初始化账户' : '查看家庭成员'}
    onClose={close}
    onConfirm={() => { close(); navigate(manager ? '/workspace/transactions?section=accounts' : '/workspace/family'); }}
  />;
}
