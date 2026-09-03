import { NavLink } from 'react-router-dom';
import type { Session } from '../api/contracts';
import { canManage, moduleItems, roleLabel } from './navigation';

export function ModuleLinks({ onSelect, mobile = false }: { onSelect?: () => void; mobile?: boolean }) {
  return (
    <nav className="module-nav" aria-label={mobile ? '移动模块导航' : '模块导航'}>
      {moduleItems.map(item => {
        const Icon = item.icon;
        return (
          <NavLink key={item.key} to={item.path} onClick={onSelect} aria-label={item.label} className={({ isActive }) => `module-link${isActive ? ' is-active' : ''}`}>
            <Icon size="default" />
            <span>{item.label}</span>
          </NavLink>
        );
      })}
    </nav>
  );
}

export function ModuleSidebar({ session }: { session: Session }) {
  return (
    <aside className="module-sidebar">
      <div className="family-context">
        <div className="family-avatar" aria-hidden="true">家</div>
        <div>
          <strong>我的家庭</strong>
          <span>{roleLabel(session.role)}</span>
        </div>
      </div>
      <ModuleLinks />
      <div className="sidebar-footer">
        <span className="presence-dot" aria-hidden="true" />
        <span>{canManage(session.role) ? '可管理家庭财务' : '共享查看与个人记账'}</span>
      </div>
    </aside>
  );
}
