import { useCallback, useContext, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
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
import { AppRail } from './AppRail';
import { MobileModuleDrawer } from './MobileModuleDrawer';
import { ModuleSidebar } from './ModuleSidebar';
import { WorkspaceHeader } from './WorkspaceHeader';

export const SIDEBAR_PREFERENCE_KEY = 'family-finance:module-sidebar-collapsed';

const pendingRequest: RequestFn = () => new Promise(() => undefined);

function WorkspaceContent({ session }: { session: Session }) {
  const location = useLocation();
  const auth = useContext(AuthContext);
  const request = auth?.request ?? pendingRequest;
  if (location.pathname === '/workspace/overview') return <DashboardPage request={request} role={session.role} displayName={session.displayName} />;
  if (location.pathname === '/workspace/transactions') return <TransactionsPage request={request} role={session.role} userId={session.userId} />;
  if (location.pathname === '/workspace/budgets') return <BudgetsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/recurring') return <RecurringPage request={request} role={session.role} userId={session.userId} />;
  if (location.pathname === '/workspace/assets') return <AssetsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/investments') return <InvestmentsPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/loans') return <LoansPage request={request} role={session.role} userId={session.userId} />;
  if (location.pathname === '/workspace/notifications') return <NotificationsPage request={request} />;
  if (location.pathname === '/workspace/family') return <FamilyPage request={request} role={session.role} />;
  if (location.pathname === '/workspace/settings') return <ChangePasswordPage />;
  return <DashboardPage request={request} role={session.role} displayName={session.displayName} />;
}

export function WorkspaceLayout({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => localStorage.getItem(SIDEBAR_PREFERENCE_KEY) === 'true');
  const [mobileOpen, setMobileOpen] = useState(false);
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
      <AppRail />
      {!sidebarCollapsed && <ModuleSidebar session={session} />}
      <div className="workspace-column">
        <WorkspaceHeader
          session={session}
          sidebarCollapsed={sidebarCollapsed}
          onToggleSidebar={toggleSidebar}
          mobileTriggerRef={mobileTriggerRef}
          onOpenMobile={() => setMobileOpen(true)}
          onLogout={onLogout}
        />
        <main className="workspace-main"><WorkspaceContent session={session} /></main>
      </div>
      <MobileModuleDrawer open={mobileOpen} onClose={closeMobile} />
    </div>
  );
}
