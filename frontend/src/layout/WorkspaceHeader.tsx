import Button from '@douyinfe/semi-ui/lib/es/button';
import { IconBell, IconMenu, IconSidebar } from '@douyinfe/semi-icons';
import type { Session } from '../api/contracts';
import { canManage, roleLabel } from './navigation';

export function WorkspaceHeader({
  session,
  sidebarCollapsed,
  onToggleSidebar,
  mobileTriggerRef,
  onOpenMobile,
  onLogout
}: {
  session: Session;
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
  mobileTriggerRef: React.RefObject<HTMLButtonElement | null>;
  onOpenMobile: () => void;
  onLogout: () => void;
}) {
  return (
    <header className="workspace-header">
      <div className="header-leading">
        <button ref={mobileTriggerRef} type="button" className="icon-button mobile-menu-trigger" aria-label="打开模块导航" onClick={onOpenMobile}>
          <IconMenu />
        </button>
        <button type="button" className="icon-button desktop-sidebar-trigger" aria-label={sidebarCollapsed ? '显示模块栏' : '隐藏模块栏'} onClick={onToggleSidebar}>
          <IconSidebar />
        </button>
        <span className="workspace-crumb">家账 / 我的家庭</span>
      </div>
      <div className="header-actions">
        {canManage(session.role) && <Button theme="borderless" size="small">邀请成员</Button>}
        {session.role === 'OWNER' && <Button theme="borderless" size="small">管理家庭</Button>}
        <button type="button" className="icon-button notification-button" aria-label="查看提醒"><IconBell /></button>
        <div className="user-chip">
          <span className="user-avatar" aria-hidden="true">{session.displayName.slice(0, 1)}</span>
          <div><strong>{session.displayName}</strong><span>{roleLabel(session.role)}</span></div>
        </div>
        <Button theme="borderless" size="small" onClick={onLogout}>退出</Button>
      </div>
    </header>
  );
}
