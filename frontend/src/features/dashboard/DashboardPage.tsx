import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowDownLeft, ArrowUpRight, ArrowRight, CircleAlert, Wallet } from 'lucide-react';
import type { Dashboard, HouseholdRole, LoanDebtOverview, NetWorth } from '../../api/contracts';
import { localYearMonth } from '../../shared/runtime';
import { DateField } from '../../shared/DateField';
import { QueryState, PageScaffold, money, type RequestFn } from '../common';
import { FlowChart, HistoryChart } from '../visuals';
import { ApiError } from '../../api/client';
import { HomePortfolio } from './HomePortfolio';
import { NetWorthDetails } from './NetWorthDetails';
import './dashboard-focus.scss';

export function DashboardPage({ request, role }: { request: RequestFn; role: HouseholdRole }) {
  const [month, setMonth] = useState(localYearMonth());
  const [detailsOpen, setDetailsOpen] = useState(false);
  const dashboard = useQuery({ queryKey: ['dashboard', month], queryFn: () => request<Dashboard>(`/api/dashboard?month=${month}&rollupCategories=true`) });
  const netWorth = useQuery({ queryKey: ['net-worth'], queryFn: () => request<NetWorth>('/api/net-worth') });
  const loanDebt = useQuery({ queryKey: ['loans', 'debt-overview'], queryFn: () => request<LoanDebtOverview>('/api/loans/debt-overview'), retry: false });
  const needsInitialization = [dashboard.error, netWorth.error].some(error => error instanceof ApiError && error.code === 'ACCOUNTING_NOT_INITIALIZED');
  if (needsInitialization) return <PageScaffold title="家庭总览">
    <section className="data-panel" role="alert" aria-label="账户初始化">
      <h2>先核对账务起点</h2><p>确认期初余额后，就能查看家庭净资产与收支。</p>
      {role === 'MEMBER' ? <a className="panel-link" href="/workspace/family">查看家庭成员</a> : <><a className="panel-link" href="/workspace/transactions?section=accounts">去初始化账户<ArrowRight size={16} aria-hidden="true"/></a><details><summary>现金账户已确认，报表仍未完整？</summary><p>核对历史记录的入账方式，不要重复记账。</p><ul><li><a href="/workspace/assets">核对资产记录</a></li><li><a href="/workspace/loans">核对贷款记录</a></li><li><a href="/workspace/investments">核对投资记录</a></li><li><a href="/workspace/transactions">核对历史流水</a></li></ul></details></>}
    </section>
  </PageScaffold>;
  const history = (netWorth.data?.history ?? []).filter(row => row.netWorth != null);
  return <PageScaffold title="家庭总览" className="home-focus">
    {loanDebt.data && loanDebt.data.overdueInstallments > 0 && <a href="/workspace/loans" className="home-attention"><CircleAlert size={18} aria-hidden="true"/><span>贷款逾期待还 {money(loanDebt.data.overdueAmount)}</span><ArrowRight size={16} aria-hidden="true"/></a>}
    <div className="home-top-grid">
      <section className="home-wealth home-surface" aria-label="当前家庭净资产">
        <header className="home-section-heading"><h2><Wallet size={19} aria-hidden="true"/>净资产</h2><button className="home-icon-button" aria-label="查看净资产详情" onClick={() => setDetailsOpen(true)}><ArrowUpRight size={21}/></button></header>
        <QueryState loading={netWorth.isLoading} error={netWorth.error}>
          <strong className="home-wealth-value">{money(netWorth.data?.netWorth)}</strong>
          <div className="home-wealth-breakdown"><span>总资产 <b>{money(netWorth.data?.asset)}</b></span><span>总负债 <b>{money(netWorth.data?.liability)}</b></span></div>
          {!!netWorth.data?.unconverted?.length && <a className="home-data-status" href="/workspace/investments?tab=rates"><CircleAlert size={15}/>汇率待补齐<ArrowRight size={14}/></a>}
          {netWorth.data?.investment.missingPrice && <span className="home-data-status" title="部分持仓缺价，净资产包含成本估算。">含成本估算</span>}
          {history.length > 1 && <div className="home-wealth-chart"><HistoryChart data={history}/></div>}
        </QueryState>
      </section>
      <section className="home-cash home-surface" aria-label="月度收支">
        <header className="home-section-heading"><h2>收支</h2><DateField aria-label="收支月份" mode="month" allowClear={false} value={month} onChange={e => { if(e.target.value) setMonth(e.target.value); }}/></header>
        <QueryState loading={dashboard.isLoading} error={dashboard.error}>
          <div className="home-cash-metrics"><div><span><ArrowDownLeft size={16} aria-hidden="true"/>收入</span><strong>{money(dashboard.data?.summary.income)}</strong></div><div><span><ArrowUpRight size={16} aria-hidden="true"/>支出</span><strong>{money(dashboard.data?.summary.expense)}</strong></div><div><span>结余</span><strong>{money(dashboard.data?.summary.balance)}</strong></div></div>
          {!!dashboard.data?.daily.length && <div className="home-cash-chart"><FlowChart points={dashboard.data.daily.map(row => ({ label: row.date.slice(8)+'日', income: row.income, expense: row.expense }))} compact/></div>}
          {!dashboard.data?.daily.length && <p className="home-empty-inline">本月还没有收支</p>}
        </QueryState>
        <footer className="home-cash-footer"><a className="home-link" href="/workspace/transactions">收支明细<ArrowRight size={16}/></a><a className="home-record-button" href="/workspace/transactions?create=1">记一笔<ArrowUpRight size={16}/></a></footer>
      </section>
    </div>
    <HomePortfolio request={request} stale={netWorth.data?.investment.stalePrice ?? false}/>
    {detailsOpen && <NetWorthDetails request={request} data={netWorth.data} onClose={() => setDetailsOpen(false)}/>}
  </PageScaffold>;
}
