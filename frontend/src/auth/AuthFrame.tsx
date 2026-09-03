import type { ReactNode } from 'react';

export function AuthFrame({ eyebrow, title, description, children, footer }: {
  eyebrow: string;
  title: string;
  description: string;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <main className="auth-page">
      <section className="auth-story" aria-label="家账介绍">
        <div className="auth-brand-lockup">
          <span className="ledger-mark" aria-hidden="true"><b>家</b><b>账</b></span>
          <span>家账</span>
        </div>
        <div className="auth-story-copy">
          <p className="auth-eyebrow">一家人的财务工作台</p>
          <h1>把家庭账目放在<br />同一张桌面上。</h1>
          <p>收支、资产、投资与贷款各归其位。每位家庭成员只看到自己应该看到的内容。</p>
        </div>
        <div className="ledger-lines" aria-hidden="true"><i /><i /><i /></div>
      </section>
      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-panel-inner">
          <p className="auth-eyebrow">{eyebrow}</p>
          <h2 id="auth-title">{title}</h2>
          <p className="auth-description">{description}</p>
          {children}
          <div className="auth-footer">{footer}</div>
        </div>
      </section>
    </main>
  );
}
