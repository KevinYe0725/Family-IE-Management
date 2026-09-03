import { useEffect, useRef } from 'react';
import { ModuleLinks } from './ModuleSidebar';

export function MobileModuleDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    closeRef.current?.focus();
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="mobile-drawer-layer">
      <div className="mobile-drawer-backdrop" data-testid="mobile-drawer-backdrop" onMouseDown={onClose} />
      <section className="mobile-drawer" role="dialog" aria-modal="true" aria-label="模块导航">
        <div className="mobile-drawer-header">
          <div><span className="mini-mark" aria-hidden="true">家</span><strong>家账模块</strong></div>
          <button ref={closeRef} type="button" className="icon-button" aria-label="关闭模块导航" onClick={onClose}>×</button>
        </div>
        <ModuleLinks mobile onSelect={onClose} />
      </section>
    </div>
  );
}
