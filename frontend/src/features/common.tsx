import type { ReactNode } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import { IconClose, IconPlus } from '@douyinfe/semi-icons';
import { ApiError, type ApiRequestOptions } from '../api/client';
import type { HouseholdRole } from '../api/contracts';

export type RequestFn = <T>(path: string, options?: ApiRequestOptions) => Promise<T>;

export function money(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return String(value);
  return `${numeric < 0 ? '-' : ''}¥${Math.abs(numeric).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function dateText(value: string | null | undefined): string {
  if (!value) return '—';
  const raw = value.slice(0, 10);
  const [year, month, day] = raw.split('-');
  return year && month && day ? `${year}.${month}.${day}` : value;
}

export function PageScaffold({ eyebrow = '家账工作区', title, description, primaryAction, readonly, children }: {
  eyebrow?: string; title: string; description: string; primaryAction?: { label: string; onClick: () => void }; readonly?: boolean; children: ReactNode;
}) {
  return <section className="feature-page" aria-labelledby="page-title">
    <header className="page-heading">
      <div><p className="section-kicker">{eyebrow}</p><h1 id="page-title">{title}</h1><p>{description}</p></div>
      {primaryAction && <Button aria-label={primaryAction.label} theme="solid" type="primary" icon={<IconPlus />} onClick={primaryAction.onClick}>{primaryAction.label}</Button>}
    </header>
    {readonly && <div className="readonly-note">当前为只读协作视图</div>}
    <div className="feature-content">{children}</div>
  </section>;
}

export function QueryState({ loading, error, empty, emptyTitle, emptyDetail, children }: {
  loading: boolean; error?: unknown; empty?: boolean; emptyTitle?: string; emptyDetail?: string; children: ReactNode;
}) {
  if (loading) return <div className="query-state" role="status"><span className="loading-rule" aria-hidden="true" />正在读取家庭数据</div>;
  if (error) {
    const apiError = error instanceof ApiError ? error : null;
    return <div className="query-state error-state" role="alert"><strong>这部分数据暂时无法读取</strong><span>{error instanceof Error ? error.message : '请稍后刷新页面'}</span>{apiError?.requestId && <small>请求 ID：{apiError.requestId}</small>}</div>;
  }
  if (empty) return <div className="query-state empty-state"><span className="empty-ledger" aria-hidden="true"><i /><i /><i /></span><div><strong>{emptyTitle ?? '暂无数据'}</strong><span>{emptyDetail ?? '完成第一笔记录后，这里会展示服务器数据。'}</span></div></div>;
  return <>{children}</>;
}

export function Drawer({ open, title, description, onClose, children }: { open: boolean; title: string; description?: string; onClose: () => void; children: ReactNode }) {
  if (!open) return null;
  return <div className="sheet-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <aside className="side-sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title">
      <header><div><h2 id="sheet-title">{title}</h2>{description && <p>{description}</p>}</div><button className="icon-button" aria-label="关闭" onClick={onClose}><IconClose /></button></header>
      <div className="sheet-body">{children}</div>
    </aside>
  </div>;
}

export function ConfirmDialog({ open, title, detail, confirmLabel = '确认', danger, onConfirm, onClose }: { open: boolean; title: string; detail: ReactNode; confirmLabel?: string; danger?: boolean; onConfirm: () => void; onClose: () => void }) {
  if (!open) return null;
  return <div className="sheet-backdrop dialog-backdrop" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
      <h2 id="confirm-title">{title}</h2><div className="confirm-detail">{detail}</div>
      <footer><Button onClick={onClose}>取消</Button><Button theme="solid" type={danger ? 'danger' : 'primary'} onClick={onConfirm}>{confirmLabel}</Button></footer>
    </section>
  </div>;
}

export function FormError({ error }: { error: unknown }) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  return <div className="form-alert" role="alert">{error instanceof Error ? error.message : '保存失败，请检查后重试'}{apiError?.requestId && <div className="request-id">请求 ID：{apiError.requestId}</div>}</div>;
}

export const isManager = (role: HouseholdRole) => role === 'OWNER' || role === 'ADMIN';

export function StatusTag({ tone = 'neutral', children }: { tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'blue'; children: ReactNode }) {
  return <span className={`status-tag ${tone}`}>{children}</span>;
}

export function DataPanel({ title, meta, action, children, className = '' }: { title: string; meta?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`data-panel ${className}`}><header><div><h2>{title}</h2>{meta && <p>{meta}</p>}</div>{action}</header><div className="panel-body">{children}</div></section>;
}
