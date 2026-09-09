import { useCallback, useContext, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { Session } from '../api/contracts';
import { AuthContext } from '../auth/AuthProvider';
import type { RequestFn } from '../features/common';
import { AssetsPage } from '../features/asset/AssetsPage';
import { BudgetsPage } from '../features/budget/BudgetsPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { FamilyPage } from '../features/family/FamilyPage';
import { InvestmentsPage } from '../features/investment/InvestmentsPage';
import { TransactionsPage } from '../features/ledger/TransactionsPage';
import { LoansPage } from '../features/loan/LoansPage';
import { NotificationsPage } from '../features/notification/NotificationsPage';
import { RecurringPage } from '../features/recurring/RecurringPage';
import { ChangePasswordPage } from '../auth/ChangePasswordPage';
import { AiSettingsCard } from '../features/ai/AiSettingsCard';
import { MobileModuleDrawer } from './MobileModuleDrawer';
import { ModuleSidebar } from './ModuleSidebar';
import { WorkspaceHeader } from './WorkspaceHeader';
import { PluginPage } from '../extensions/registry';
import { AccountInitializationGuide } from './AccountInitializationGuide';
import { RecurringBillingGuide } from './RecurringBillingGuide';

export const SIDEBAR_PREFERENCE_KEY = 'family-finance:module-sidebar-collapsed';

const pendingRequest: RequestFn = () => new Promise(() => undefined);

function WorkspaceContent({ session }: { session: Session }) {
  const location = useLocation();
  const navigate = useNavigate();
  const consumeInvite = useCallback(() => navigate('/workspace/family', { replace: true }), [navigate]);
  const auth = useContext(AuthContext);
  const request = auth?.request ?? pendingRequest;
  if (location.pathname.startsWith('/workspace/extensions/')) return <PluginPage path={location.pathname} request={request} />;
  if (location.pathname === '/workspace/overview') return <DashboardPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/transactions') return <TransactionsPage request={request} role={session.role} userId={session.userId} requestedSection={new URLSearchParams(location.search).get('section') === 'accounts' ? 'accounts' : undefined} />;
  if (location.pathname === '/workspace/budgets') return <BudgetsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/recurring') return <RecurringPage request={request} role={session.role} userId={session.userId} />;
  if (location.pathname === '/workspace/assets') return <AssetsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/investments') return <InvestmentsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/loans') return <LoansPage request={request} role={session.role} userId={session.userId} />;
  if (location.pathname === '/workspace/notifications') return <NotificationsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/family') return <FamilyPage request={request} role={session.role} inviteRequested={new URLSearchParams(location.search).get('action') === 'invite'} onInviteRequestHandled={consumeInvite} />;
  if (location.pathname === '/workspace/settings') return <div className="account-settings-page"><ChangePasswordPage /><AiSettingsCard key={session.userId} request={request} /></div>;
  return <DashboardPage request={request} role={session.role} />;
}

export function WorkspaceLayout({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const auth = useContext(AuthContext);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => localStorage.getItem(SIDEBAR_PREFERENCE_KEY) === 'true');
  const [mobileOpen, setMobileOpen] = useState(false);
  const [accountGuideDecision, setAccountGuideDecision] = useState<'waiting' | 'show' | 'dismissed'>('waiting');
  const mobileTriggerRef = useRef<HTMLButtonElement>(null);

  const closeMobile = useCallback(() => {
    setMobileOpen(false);
    queueMicrotask(() => mobileTriggerRef.current?.focus());
  }, []);

  function toggleSidebar() {
    setSidebarCollapsed(value => {
      const next = !value;
      localStorage.setItem(SIDEBAR_PREFERENCE_KEY, String(next));
      return next;
    });
  }

  return (
    <div className={`workspace-shell${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
      <ModuleSidebar collapsed={sidebarCollapsed} onToggle={toggleSidebar} />
      <div className="workspace-column">
        <WorkspaceHeader
          session={session}
          mobileTriggerRef={mobileTriggerRef}
          onOpenMobile={() => setMobileOpen(true)}
          onLogout={onLogout}
        />
        <main className="workspace-main"><WorkspaceContent session={session} /></main>
      </div>
      <MobileModuleDrawer open={mobileOpen} onClose={closeMobile} />
      {auth?.status === 'authenticated' && <>
        <AccountInitializationGuide
          key={`account-init:${session.userId}:${session.householdId}`}
          session={session}
          request={auth.request}
          onDecisionChange={setAccountGuideDecision}
        />
        {accountGuideDecision === 'dismissed' && <RecurringBillingGuide key={`recurring-guide:${session.userId}:${session.householdId}`} session={session} request={auth.request} />}
      </>}
    </div>
  );
}
