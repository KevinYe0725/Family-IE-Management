import './action-dialog.scss';
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { X, Plus, CircleAlert, LoaderCircle } from 'lucide-react';
import { EmptyIllustration } from './visuals';
import { ApiError, type ApiRequest } from '../api/client';
import type { HouseholdRole } from '../api/contracts';
import { useDraftProtection } from '../shared/draft-guard';
import { formatMoney } from '../shared/currency';

export type RequestFn = ApiRequest;

export const money = formatMoney;

export function dateText(value: string | null | undefined): string {
  if (!value) return '—';
  const raw = value.slice(0, 10);
  const [year, month, day] = raw.split('-');
  return year && month && day ? `${year}.${month}.${day}` : value;
}

export function PageScaffold({ title, description, primaryAction, readonly, children, className='' }: {
  title: string; description?: string; primaryAction?: { label: string; onClick: () => void }; readonly?: boolean; children: ReactNode;className?:string;
}) {
  return <section className={`feature-page ${className}`} aria-labelledby="page-title">
    <header className="page-heading">
      <div><h1 id="page-title">{title}</h1>{description && <p>{description}</p>}</div>
      {primaryAction && <Button aria-label={primaryAction.label} theme="solid" type="primary" icon={<Plus size={17} aria-hidden="true"/>} onClick={primaryAction.onClick}>{primaryAction.label}</Button>}
    </header>
    {readonly && <div className="readonly-note">当前为只读协作视图</div>}
    <div className="feature-content">{children}</div>
  </section>;
}

export function QueryState({ loading, error, empty, emptyTitle, emptyDetail, children }: {
  loading: boolean; error?: unknown; empty?: boolean; emptyTitle?: string; emptyDetail?: string; children: ReactNode;
}) {
  if (loading) return <div className="query-state" role="status"><LoaderCircle className="loading-spinner" size={24} aria-hidden="true"/>正在读取家庭数据</div>;
  if (error) {
    const apiError = error instanceof ApiError ? error : null;
    if(apiError?.code==='FX_RATE_MISSING')return <div className="query-state error-state" role="status"><CircleAlert size={26}/><strong>待补充历史汇率</strong><span>原币账务已保留，人民币统计暂不完整。</span><a href="/workspace/investments?tab=rates">查看并更新汇率</a></div>;
    if (apiError?.code === 'ACCOUNTING_NOT_INITIALIZED') return <div className="query-state error-state" role="alert"><CircleAlert size={26} aria-hidden="true"/><strong>请先核对账务起点</strong><span>每个现金账户都需确认期初余额，包含未使用的默认账户和零余额。历史资产、贷款或投资记录需要另行核对，不能推算补记。</span><a href="/workspace/transactions?section=accounts">去确认账户期初余额</a></div>;
    return <div className="query-state error-state" role="alert"><CircleAlert size={26} aria-hidden="true"/><strong>这部分数据暂时无法读取</strong><span>{error instanceof Error ? error.message : '请稍后刷新页面'}</span>{apiError?.requestId && <small>请求 ID：{apiError.requestId}</small>}</div>;
  }
  if (empty) return <div className="query-state empty-state"><EmptyIllustration/><div><strong>{emptyTitle ?? '暂无数据'}</strong><span>{emptyDetail ?? '当前没有可展示的记录。'}</span></div></div>;
  return <>{children}</>;
}

const modalStack: string[] = [];
let previousOverflow = '';
function useModal(open: boolean, onClose: () => void) {
  const id = useId();
  const ref = useRef<HTMLElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    if (modalStack.length === 0) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; }
    modalStack.push(id);
    const focusable = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]') ?? []).filter(el => !el.hidden && el.getAttribute('aria-disabled') !== 'true');
    (focusable()[0] ?? ref.current)?.focus();
    function keys(e: KeyboardEvent) {
      if (modalStack.at(-1) !== id) return;
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close.current(); }
      if (e.key === 'Tab') {
        const items = focusable();
        if (!items.length) { e.preventDefault(); ref.current?.focus(); return; }
        const i = items.indexOf(document.activeElement as HTMLElement);
        if (e.shiftKey && i <= 0) { e.preventDefault(); items.at(-1)?.focus(); }
        else if (!e.shiftKey && (i === items.length-1 || i === -1)) { e.preventDefault(); items[0].focus(); }
      }
    }
    document.addEventListener('keydown', keys, true);
    return () => {
      document.removeEventListener('keydown', keys, true);
      const wasTop = modalStack.at(-1) === id;
      const i = modalStack.indexOf(id); if (i !== -1) modalStack.splice(i, 1);
      if (!modalStack.length) document.body.style.overflow = previousOverflow;
      if (wasTop && previous?.isConnected) previous.focus();
    };
  }, [open, id]);
  return { id, ref };
}

export function Drawer({ open, title, description, onClose, children, draft, busy = false, sessionKey, savedKey, onSessionStart, presentation='drawer', size='medium',className='' }: {
  open: boolean; title: string; description?: string; onClose: () => void; children: ReactNode;
  draft?: unknown; busy?: boolean; sessionKey?: unknown; savedKey?: unknown; onSessionStart?: () => void; presentation?:'drawer'|'modal';size?:'medium'|'wide';className?:string;
}) {
  const [confirming, setConfirming] = useState(false);
  const protection = useDraftProtection({ active: open, draft, busy, sessionKey, savedKey, onDiscard: onClose });
  const start = useRef(onSessionStart); start.current = onSessionStart;
  useLayoutEffect(() => { setConfirming(false); if (open) start.current?.(); }, [open, sessionKey]);
  const requestClose = () => { if (busy) return; if (protection.dirty) setConfirming(true); else onClose(); };
  const {id,ref} = useModal(open,requestClose);
  const Panel=presentation==='modal'?'section':'aside';
  if (!open) return null;
  return createPortal(<><div className={`sheet-backdrop${presentation==='modal'?' action-dialog-backdrop':''}`} onMouseDown={event => event.target === event.currentTarget && requestClose()}>
    <Panel ref={ref} tabIndex={-1} className={`side-sheet${presentation==='modal'?` action-dialog action-dialog--${size}`:''} ${className}`} role="dialog" aria-modal="true" aria-labelledby={id}>
      <header><div><h2 id={id}>{title}</h2>{description && <p>{description}</p>}</div><button type="button" className="icon-button" aria-label="关闭" disabled={busy} onClick={requestClose}><X size={20} aria-hidden="true"/></button></header>
      <div className="sheet-body"><fieldset disabled={busy} style={{ border: 0, padding: 0, margin: 0, minWidth: 0, display: 'grid', gap: 'inherit' }} onSubmitCapture={event => { if (busy) { event.preventDefault(); event.stopPropagation(); } }}>{children}</fieldset></div>
    </Panel>
  </div><ConfirmDialog open={confirming} title="放弃未保存的修改？" detail="关闭后，本次尚未保存的输入将被清除。" cancelLabel="继续编辑" confirmLabel="放弃修改" onClose={() => setConfirming(false)} onConfirm={() => { setConfirming(false); onClose(); }} /></>, document.body);
}

export function ActionDialog(props:Omit<Parameters<typeof Drawer>[0],'presentation'>){
  return <Drawer {...props} presentation="modal"/>;
}

export function CenteredModal({ width, ...props }: Omit<Parameters<typeof ActionDialog>[0], 'size'> & { width?: number }) {
  return <ActionDialog {...props} size={width != null && width <= 680 ? 'medium' : 'wide'} />;
}

export function ConfirmDialog({ open, title, detail, banner, confirmLabel = '确认', cancelLabel = '取消', confirmDisabled = false, danger, onConfirm, onClose, loading = false, className = '' }: { open: boolean; title: string; detail: ReactNode; banner?: ReactNode; confirmLabel?: string; cancelLabel?: string; confirmDisabled?: boolean; danger?: boolean; onConfirm: () => void; onClose: () => void; loading?: boolean; className?: string }) {
  const {id,ref} = useModal(open,onClose);
  if (!open) return null;
  return createPortal(<div className="sheet-backdrop dialog-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section ref={ref} tabIndex={-1} className={`confirm-dialog ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby={id}>
      {banner}<div className="confirmation-symbol"><CircleAlert size={24} aria-hidden="true"/></div><h2 id={id}>{title}</h2><div className="confirm-detail">{detail}</div>
      <footer><Button onClick={onClose}>{cancelLabel}</Button><Button theme="solid" loading={loading} disabled={confirmDisabled} type={danger ? 'danger' : 'primary'} onClick={onConfirm}>{confirmLabel}</Button></footer>
    </section>
  </div>, document.body);
}

export function ModalDialog({ open, title, description, onClose, children, footer, className = '' }: {
  open: boolean; title: string; description?: string; onClose: () => void; children: ReactNode; footer?: ReactNode; className?: string;
}) {
  const { id, ref } = useModal(open, onClose);
  if (!open) return null;
  return createPortal(<div className="sheet-backdrop dialog-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section ref={ref} tabIndex={-1} className={`confirm-dialog content-dialog ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby={id}>
      <header className="content-dialog-header"><div><h2 id={id}>{title}</h2>{description && <p>{description}</p>}</div><button type="button" className="icon-button" aria-label="关闭" onClick={onClose}><X size={19} aria-hidden="true" /></button></header>
      <div className="content-dialog-body">{children}</div>
      {footer && <footer>{footer}</footer>}
    </section>
  </div>, document.body);
}

export function FormError({ error, scopeKey, compact=false }: { error: unknown; scopeKey?: unknown;compact?:boolean }) {
  const id = useId();
  const ref = useRef<HTMLDivElement>(null);
  const fields = error instanceof ApiError ? error.fields : undefined;
  useLayoutEffect(() => {
    if (!error || !ref.current) return;
    const root = ref.current.closest('form') ?? ref.current.closest('[role="dialog"]');
    const controls = Array.from(root?.querySelectorAll<HTMLElement>('input, select, textarea, [data-field]') ?? []);
    const linked: Array<{ element: HTMLElement; previous: string | null; invalid: string | null }> = [];
    Object.keys(fields ?? {}).forEach((field, index) => {
      const element = controls.find(control => control.getAttribute('name') === field || control.id === field || control.getAttribute('data-field') === field)
        ?? controls.find(control => control.getAttribute('name') === field.replace(/^(property|vehicle)\./, ''));
      if (!element) return;
      const previous = element.getAttribute('aria-describedby');
      linked.push({ element, previous, invalid: element.getAttribute('aria-invalid') });
      element.setAttribute('aria-describedby', [previous, `${id}-${index}`].filter(Boolean).join(' '));
      element.setAttribute('aria-invalid', 'true');
    });
    const target = linked[0]?.element ?? ref.current;
    target.focus(); target.scrollIntoView?.({ block: 'nearest' });
    return () => linked.forEach(({ element, previous, invalid }) => {
      if (previous === null) element.removeAttribute('aria-describedby'); else element.setAttribute('aria-describedby', previous);
      if (invalid === null) element.removeAttribute('aria-invalid'); else element.setAttribute('aria-invalid', invalid);
    });
  }, [error, fields, id, scopeKey]);
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  return <div ref={ref} tabIndex={-1} className="form-alert" role="alert">{error instanceof Error ? error.message : '保存失败，请检查后重试'}{apiError?.fields && <ul>{Object.entries(apiError.fields).map(([field,message], index)=><li id={`${id}-${index}`} key={field}>{message}</li>)}</ul>}{apiError?.requestId && (compact?<details className="error-diagnostics"><summary tabIndex={0}>错误详情</summary><div className="request-id">请求 ID：{apiError.requestId}</div></details>:<div className="request-id">请求 ID：{apiError.requestId}</div>)}</div>;
}

export const isManager = (role: HouseholdRole) => role === 'OWNER' || role === 'ADMIN';

export function StatusTag({ tone = 'neutral', children }: { tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'blue'; children: ReactNode }) {
  return <span className={`status-tag ${tone}`}>{children}</span>;
}

export function DataPanel({ title, meta, action, children, className = '' }: { title: string; meta?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`data-panel ${className}`}><header><div><h2>{title}</h2>{meta && <p>{meta}</p>}</div>{action}</header><div className="panel-body">{children}</div></section>;
}
