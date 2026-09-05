import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AnnualStatsPage from './AnnualStatsPage';
import { supportedPlugins, PluginPage } from '../../extensions/registry';
import type { RequestFn } from '../../features/common';

it('displays annual totals and changes the requested year', async () => {
  const request = vi.fn(async () => ({ year: 2026, averageMonthCount: 12,
    summary: { income: '1200.00', expense: '600.00', balance: '600.00', averageIncome: '100.00', averageExpense: '50.00', averageBalance: '50.00' },
    months: Array.from({ length: 12 }, (_, index) => ({ month: index + 1, income: '100.00', expense: '50.00', balance: '50.00' })) }));
  render(<QueryClientProvider client={new QueryClient()}><AnnualStatsPage request={request as RequestFn} /></QueryClientProvider>);
  expect(await screen.findByText('¥1,200.00')).toBeInTheDocument();
  expect(screen.getByText(/全年合计 ÷ 12/)).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText('统计年份'), '2025');
  expect(request).toHaveBeenCalledWith('/api/plugins/annual-stats?year=2025');
});

it('ignores unknown or incompatible extensions and blocks a disabled route', () => {
  const descriptor = { id: 'annual-stats', apiVersion: 1, version: '1', name: '年度统计', description: '', path: '/workspace/extensions/annual-stats', capabilities: ['ledger.read'] };
  expect(supportedPlugins([descriptor, { ...descriptor, id: 'remote-code' }, { ...descriptor, apiVersion: 2 }])).toEqual([descriptor]);
  render(<PluginPage path={descriptor.path} request={vi.fn() as RequestFn} />);
  expect(screen.getByText(/此扩展未启用/)).toBeInTheDocument();
});
