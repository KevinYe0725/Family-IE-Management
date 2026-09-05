import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataPanel, PageScaffold, QueryState, money, type RequestFn } from '../../features/common';

interface AnnualReport {
  year: number; averageMonthCount: number;
  summary: { income: string; expense: string; balance: string; averageIncome: string; averageExpense: string; averageBalance: string };
  months: { month: number; income: string; expense: string; balance: string }[];
}

export default function AnnualStatsPage({ request }: { request: RequestFn }) {
  const [year, setYear] = useState(new Date().getFullYear());
  const report = useQuery({ queryKey: ['plugin', 'annual-stats', year], queryFn: () => request<AnnualReport>(`/api/plugins/annual-stats?year=${year}`) });
  const summary = report.data?.summary;
  return <PageScaffold title="年度统计" description="从每一笔家庭收支，回看一年的收入、支出与结余。">
    <div className="toolbar"><label>统计年份<select aria-label="统计年份" value={year} onChange={e => setYear(Number(e.target.value))}>{Array.from({ length: 201 }, (_, index) => 2100 - index).map(value => <option key={value} value={value}>{value} 年</option>)}</select></label><span className="muted">年度统计扩展</span></div>
    <QueryState loading={report.isLoading} error={report.error}>
      <div className="summary-strip"><div><span>全年收入</span><strong>{money(summary?.income)}</strong></div><div><span>全年支出</span><strong>{money(summary?.expense)}</strong></div><div><span>全年结余</span><strong>{money(summary?.balance)}</strong></div></div>
      <DataPanel title="月平均水平" meta="全年合计 ÷ 12；没有记录的月份按零计算，当前年份也采用此口径。"><div className="summary-strip"><div><span>月平均收入</span><strong>{money(summary?.averageIncome)}</strong></div><div><span>月平均支出</span><strong>{money(summary?.averageExpense)}</strong></div><div><span>月平均结余</span><strong>{money(summary?.averageBalance)}</strong></div></div></DataPanel>
      <DataPanel title="逐月收支" meta={`${year} 年 · 结余 = 收入 − 支出`}><div className="annual-months"><table><thead><tr><th>月份</th><th>收入</th><th>支出</th><th>结余</th></tr></thead><tbody>{report.data?.months.map(row => <tr key={row.month}><th scope="row">{row.month} 月</th><td>{money(row.income)}</td><td>{money(row.expense)}</td><td>{money(row.balance)}</td></tr>)}</tbody></table></div></DataPanel>
    </QueryState>
  </PageScaffold>;
}
