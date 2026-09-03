import { useCallback, useRef, useState } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { IconPlus } from '@douyinfe/semi-icons';
import { useLocation } from 'react-router-dom';
import type { Session } from '../api/contracts';
import { ChangePasswordPage } from '../auth/ChangePasswordPage';
import { AppRail } from './AppRail';
import { MobileModuleDrawer } from './MobileModuleDrawer';
import { ModuleSidebar } from './ModuleSidebar';
import { WorkspaceHeader } from './WorkspaceHeader';
import { canManage, pageMeta } from './navigation';

export const SIDEBAR_PREFERENCE_KEY = 'family-finance:module-sidebar-collapsed';

function WorkspacePlaceholder({ session }: { session: Session }) {
  const location = useLocation();
  const meta = pageMeta[location.pathname] ?? pageMeta['/workspace/overview']!;
  if (location.pathname === '/workspace/settings') return <ChangePasswordPage />;
  return (
    <section className="workspace-placeholder" aria-labelledby="page-title">
      <div className="page-heading">
        <div>
          <p className="section-kicker">{meta.eyebrow}</p>
          <h1 id="page-title">{meta.title}</h1>
          <p>{meta.description}</p>
        </div>
        {canManage(session.role) && location.pathname === '/workspace/transactions' && (
          <Button theme="solid" type="primary" icon={<IconPlus />}>记一笔</Button>
        )}
      </div>
      {session.role === 'MEMBER' && ['/workspace/assets', '/workspace/investments', '/workspace/loans'].includes(location.pathname) && (
        <div className="readonly-note">当前为只读协作视图</div>
      )}
      <div className="empty-workspace">
        <span className="empty-ledger" aria-hidden="true"><i /><i /><i /></span>
        <div>
          <h2>{meta.title}正在准备</h2>
          <p>工作区结构已经就位，具体财务功能将在下一阶段接入真实家庭数据。</p>
        </div>
      </div>
    </section>
  );
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
        <main className="workspace-main"><WorkspacePlaceholder session={session} /></main>
      </div>
      <MobileModuleDrawer open={mobileOpen} onClose={closeMobile} />
    </div>
  );
}
