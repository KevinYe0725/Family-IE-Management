import type { LoanDebtOverview } from '../../api/contracts';
import { dateText, money } from '../common';

/** Whole-household debt summary card shown above the per-loan cards. */
export function LoanDebtOverviewPanel({ data }: { data: LoanDebtOverview }) {
  return (
    <section className="debt-overview" aria-label="贷款债务总览">
      <header>
        <h2>贷款债务总览</h2>
        <span>{data.count} 笔进行中贷款</span>
      </header>
      <div className="debt-overview__hero">
        <span>待还本金合计</span>
        <b>{money(data.remainingPrincipal)}</b>
      </div>
      <dl className="debt-overview__stats">
        <div>
          <dt>待还本息合计</dt>
          <dd>{money(data.remainingRepayment)}</dd>
        </div>
        <div>
          <dt>30 天内应还</dt>
          <dd>{money(data.thirtyDayDue)}</dd>
        </div>
        <div>
          <dt>累计已还现金</dt>
          <dd>{money(data.paidRepayment)}</dd>
        </div>
        <div>
          <dt>加权年利率</dt>
          <dd>{data.weightedAnnualRatePercent}%</dd>
        </div>
        <div>
          <dt>最近到期日</dt>
          <dd>{data.nextDueOn ? dateText(data.nextDueOn) : '—'}</dd>
        </div>
      </dl>
      {data.overdueInstallments > 0 && (
        <a className="debt-overview__overdue" href="/workspace/loans">
          逾期待还 {money(data.overdueAmount)}（{data.overdueInstallments} 期 · 最长逾期{' '}
          {data.overdueDays} 天）去还款
        </a>
      )}
    </section>
  );
}
