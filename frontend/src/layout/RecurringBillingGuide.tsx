import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CalendarClock } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import recurringBillingGuideImage from '../assets/recurring-billing-guide.png';
import type { ApiRequest } from '../api/client';
import type { Page, RecurringOccurrence, RecurringRule, Session } from '../api/contracts';
import { readAllPages } from '../shared/pagination';
import { ConfirmDialog } from '../features/common';

type ReminderDecision = 'waiting' | 'show' | 'dismissed';

/** Reminds once per authenticated workspace entry; closing only dismisses this login. */
export function RecurringBillingGuide({ session, request }: { session: Session; request: ApiRequest }) {
  const navigate = useNavigate();
  const [decision, setDecision] = useState<ReminderDecision>('waiting');
  const occurrences = useQuery({
    queryKey: ['recurring-occurrences', 'login-guide', session.userId, session.householdId],
    queryFn: ({ signal }) => request<Page<RecurringOccurrence>>('/api/recurring-occurrences?status=PENDING&page=0&size=50', { responseType: 'page', signal }),
    enabled: decision !== 'dismissed',
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });
  const rules = useQuery({
    queryKey: ['recurring-rules', 'login-guide', session.userId, session.householdId],
    queryFn: ({ signal }) => readAllPages(page => request<Page<RecurringRule>>(`/api/recurring-rules?includeInactive=true&page=${page}&size=50`, { responseType: 'page', signal })),
    enabled: decision !== 'dismissed',
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });
  const activeRules = useMemo(() => (rules.data ?? []).filter(rule => rule.active && !rule.paused), [rules.data]);
  const pendingCount = occurrences.data?.totalElements ?? occurrences.data?.items.length ?? 0;
  const hasRecurringState = pendingCount > 0 || activeRules.length > 0;
  const hasPendingBills = pendingCount > 0;

  useEffect(() => {
    if (decision === 'dismissed') return;
    if (occurrences.isError || rules.isError) { setDecision('dismissed'); return; }
    if (occurrences.isFetching || rules.isFetching || !occurrences.data || !rules.data) return;
    if (!hasRecurringState) { setDecision('dismissed'); return; }
    if (decision !== 'waiting') return;
    const timer = window.setTimeout(() => {
      // AccountInitializationGuide has priority when both reminders are needed.
      setDecision(document.querySelector('[role="dialog"]') ? 'dismissed' : 'show');
    }, 0);
    return () => window.clearTimeout(timer);
  }, [decision, hasRecurringState, occurrences.data, occurrences.isError, occurrences.isFetching, rules.data, rules.isError, rules.isFetching]);

  const close = () => setDecision('dismissed');
  const goToRecurring = () => { close(); navigate('/workspace/recurring'); };

  return <ConfirmDialog
    open={decision === 'show' && hasRecurringState}
    className="recurring-guide-dialog"
    banner={<div className="recurring-guide-banner"><img src={recurringBillingGuideImage} alt="家庭预算记录场景" /></div>}
    title={hasPendingBills ? '有待确认的周期账单' : '周期账单规则仍在运行'}
    detail={<div className="recurring-login-guide">
      <div className="recurring-guide-lede">
        <span className="recurring-guide-icon" aria-hidden="true"><CalendarClock size={22} /></span>
        <div><strong>{hasPendingBills ? `检测到 ${pendingCount} 条待确认账单` : `有 ${activeRules.length} 条周期规则正在运行`}</strong><span>{hasPendingBills ? '确认后才会计入家庭收支，规则会继续按计划提醒。' : '到期后会提醒你确认，再记入家庭账本。'}</span></div>
      </div>
      <p className="recurring-guide-note">关闭后，本次登录不再重复弹出；下次登录仍会再次检查。</p>
    </div>}
    cancelLabel="稍后查看"
    confirmLabel={hasPendingBills ? '前去确认' : '查看周期规则'}
    onClose={close}
    onConfirm={goToRecurring}
  />;
}
