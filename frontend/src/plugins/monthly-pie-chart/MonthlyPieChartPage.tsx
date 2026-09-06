import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { useQuery } from '@tanstack/react-query';
import { DataPanel, PageScaffold, QueryState, money, type RequestFn } from '../../features/common';

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884D8', '#82CA9D', '#FF6B6B'];

interface PieItem {
  name: string;
  amount: string;
}

interface ChartData {
  incomeByCategory: PieItem[];
  expenseByCategory: PieItem[];
  incomeByMember: PieItem[];
  expenseByMember: PieItem[];
}

interface ChartItem {
  name: string;
  amount: number;
}

function toChartData(items: PieItem[] | undefined): ChartItem[] {
  return (items ?? [])
    .map(item => ({ name: item.name, amount: Number(item.amount) }))
    .filter(item => Number.isFinite(item.amount) && item.amount > 0);
}

function renderPie(title: string, chartData: ChartItem[]) {
  if (!chartData || chartData.length === 0) {
    return <div className="query-state empty-state"><div><strong>暂无{title}数据</strong><span>本月还没有可供统计的记录。</span></div></div>;
  }
  return (
    <ResponsiveContainer width="100%" height={250}>
      <PieChart>
        <Pie
          data={chartData}
          cx="50%"
          cy="50%"
          labelLine={false}
          outerRadius={80}
          fill="#8884d8"
          dataKey="amount"
          nameKey="name"
          label={({ name, percent }: { name?: string; percent?: number }) => `${name ?? ''} ${((percent ?? 0) * 100).toFixed(0)}%`}
        >
          {chartData.map((_, index) => (
            <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip formatter={(value: unknown) => money(typeof value === 'number' || typeof value === 'string' ? value : undefined)} />
        <Legend />
      </PieChart>
    </ResponsiveContainer>
  );
}

export default function MonthlyPieChartPage({ request }: { request: RequestFn }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['monthly-pie-chart'],
    // ✅ 关键修改：直接返回 request 的结果，千万不要再 .data 了！
    queryFn: () => request<ChartData>('/api/plugins/monthly-pie-chart')
  });

  const charts = [
    { title: '按消费类型 · 收入构成', emptyTitle: '收入分类', data: toChartData(data?.incomeByCategory) },
    { title: '按消费类型 · 支出构成', emptyTitle: '支出分类', data: toChartData(data?.expenseByCategory) },
    { title: '按家庭成员 · 收入构成', emptyTitle: '成员收入', data: toChartData(data?.incomeByMember) },
    { title: '按家庭成员 · 支出构成', emptyTitle: '成员支出', data: toChartData(data?.expenseByMember) }
  ];

  return <PageScaffold title="月度收支构成" description="用四个视角查看本月收入与支出的来源。">
    <QueryState loading={isLoading} error={isError ? new Error('图表数据加载失败，请刷新重试。') : undefined}>
      <div className="plugin-grid">
        {charts.map(chart => <DataPanel key={chart.title} title={chart.title} className="chart-panel">{renderPie(chart.emptyTitle, chart.data)}</DataPanel>)}
      </div>
    </QueryState>
  </PageScaffold>;
}