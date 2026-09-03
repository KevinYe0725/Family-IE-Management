import { NavLink, useLocation } from 'react-router-dom';
import { railItems } from './navigation';

export function AppRail() {
  const location = useLocation();
  return (
    <nav className="app-rail" aria-label="应用导航" data-width="52">
      <NavLink className="rail-brand" to="/workspace/overview" aria-label="家账首页">
        <span aria-hidden="true">家</span>
      </NavLink>
      <div className="rail-links">
        {railItems.map(item => {
          const active = location.pathname === item.path
            || (item.key === 'ledger' && ['/workspace/budgets', '/workspace/recurring'].includes(location.pathname))
            || (item.key === 'settings' && location.pathname === '/workspace/family');
          const Icon = item.icon;
          return (
            <NavLink key={item.key} to={item.path} className={`rail-link${active ? ' is-active' : ''}`} aria-label={item.label} aria-current={active ? 'page' : undefined}>
              <Icon size="large" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </div>
    </nav>
  );
}
