import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StockPicker } from './StockPicker';
import { InvestmentSetup } from './InvestmentSetup';
import { InvestmentsPage } from './InvestmentsPage';
import { aggregateBars, selectBarsForRange } from './chart-data';
import type { Security } from '../../api/contracts';
import type { RequestFn } from '../common';

const security: Security = { id: 5, market: 'SZ', tsCode: '000001.SZ', name: '平安银行', active: true, securityType: 'STOCK' };
const page = (items: Security[]) => ({ items, page: 0, size: 20, totalPages: 1, totalElements: items.length, hasNext: false });
function wrap(child: React.ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>{child}</QueryClientProvider>;
}

it('selects a real catalog stock and preserves it while searching another name', async () => {
  const request = vi.fn(async (path: string) => path.includes('catalog-status') ? { state: 'READY', count: 5558 } : page(path.includes('nothing') ? [] : [security]));
  function Picker() {
    const [value, setValue] = useState<Security | null>(null);
    return <StockPicker request={request as RequestFn} value={value} onChange={setValue}/>;
  }
  render(wrap(<Picker/>));
  const user = userEvent.setup();
  const control = screen.getByRole('combobox', { name: '证券' });
  await user.click(control);
  await user.click(await screen.findByRole('option', { name: /000001\.SZ · 平安银行/ }));
  expect(screen.getByRole('combobox', { name: '证券' })).toHaveTextContent('000001.SZ · 平安银行');
  await user.click(screen.getByRole('combobox', { name: '证券' }));
  await user.type(screen.getByRole('textbox'), 'nothing');
  await screen.findByText('没有找到匹配股票，请检查代码或名称。');
  expect(screen.getByRole('combobox', { name: '证券' })).toHaveTextContent('000001.SZ · 平安银行');
  expect(screen.queryByRole('button', { name: '登记证券' })).not.toBeInTheDocument();
  expect(request.mock.calls.every(([path]) => !path.includes('/resolve'))).toBe(true);
});

it('distinguishes a failed stock search from an empty directory and provides retry', async () => {
  let failed = true;
  const request = vi.fn(async (path: string) => {
    if (path.includes('catalog-status')) return { state: 'READY', count: 5558 };
    if (failed) throw new Error('连接中断');
    return page([security]);
  });
  render(wrap(<StockPicker request={request as RequestFn} value={null} onChange={() => {}}/>));
  await screen.findByText('股票搜索暂时不可用');
  failed = false;
  await userEvent.click(screen.getByRole('button', { name: '重试搜索' }));
  await userEvent.click(screen.getByRole('combobox', { name: '证券' }));
  expect(await screen.findByRole('option', { name: /000001\.SZ · 平安银行/ })).toBeInTheDocument();
});

it('shows a catalog-status failure instead of reporting that no stocks match', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.includes('catalog-status')) throw new Error('目录状态连接中断');
    return page([]);
  });
  render(wrap(<StockPicker request={request as RequestFn} value={security} onChange={() => {}}/>));
  expect(await screen.findByText('股票目录状态暂时无法读取')).toBeInTheDocument();
  expect(screen.queryByText('没有找到匹配股票，请检查代码或名称。')).not.toBeInTheDocument();
  const control = screen.getByRole('combobox', { name: '证券' });
  expect(control).toHaveTextContent('000001.SZ · 平安银行');
  expect(control).toHaveAttribute('aria-disabled', 'true');
  expect(request.mock.calls.some(([path]) => path.includes('/api/securities/search'))).toBe(false);
});

it('does not keep routine catalog helper copy visible after a ready stock search', async () => {
  const request = vi.fn(async (path: string) => path.includes('catalog-status')
    ? { state: 'READY', count: 5558, updatedAt: '2026-09-08T08:30:00Z' }
    : page([security]));
  render(wrap(<StockPicker request={request as RequestFn} value={null} onChange={() => {}}/>));
  await waitFor(() => {
    expect(screen.queryByText(/目录更新：2026\.09\.08/)).not.toBeInTheDocument();
    expect(screen.queryByText('选择系统股票目录中的证券，无需自行登记。')).not.toBeInTheDocument();
  });
});

it('keeps the last published catalog selectable when a refresh fails', async () => {
  const request = vi.fn(async (path: string) => path.includes('catalog-status')
    ? { state: 'ERROR', count: 5558, updatedAt: '2026-09-08T08:30:00Z', error: '目录同步超时' }
    : page([security]));
  function Picker() {
    const [value, setValue] = useState<Security | null>(null);
    return <StockPicker request={request as RequestFn} value={value} onChange={setValue}/>;
  }
  render(wrap(<Picker/>));
  expect(await screen.findByText(/目录刷新失败，正在使用上次成功目录：目录同步超时/)).toBeInTheDocument();
  expect(screen.getByText(/上次成功更新：2026\.09\.08/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole('combobox', { name: '证券' }));
  await userEvent.click(await screen.findByRole('option', { name: /000001\.SZ · 平安银行/ }));
  expect(screen.getByRole('combobox', { name: '证券' })).toHaveTextContent('000001.SZ · 平安银行');
  expect(screen.getByRole('button', { name: '重试目录更新' })).toBeInTheDocument();
  expect(request.mock.calls.some(([path]) => path.includes('/api/securities/search'))).toBe(true);
});

it.each(['UNKNOWN', 'DISABLED'])('blocks new catalog search for %s even when a positive count is reported', async state => {
  const request = vi.fn(async (path: string) => path.includes('catalog-status')
    ? { state, count: 5558, updatedAt: '2026-09-08T08:30:00Z' }
    : page([security]));
  render(wrap(<StockPicker request={request as RequestFn} value={null} onChange={() => {}}/>));
  await screen.findByRole('button', { name: '重试目录状态' });
  expect(screen.queryByRole('option', { name: '000001.SZ · 平安银行' })).not.toBeInTheDocument();
  expect(request.mock.calls.some(([path]) => path.includes('/api/securities/search'))).toBe(false);
});

const account = { id: 3, name: '证券账户', brokerName: '券商', fundingAccountId: 7, currency: 'CNY', status: 'ACTIVE' as const, createdBy: 1, archivedAt: null };
const cash = { id: 7, name: '资金卡', type: 'BANK' as const, currency: 'CNY', openingBalance: '0.00', balance: '0.00', availableBalance: '0.00', openingConfirmed: true, openingOn: '2026-01-01', archivedAt: null };
const readyAccounts = (data = [account]) => ({ data, isLoading: false, error: null, refetch: vi.fn() });
const readyCashAccounts = (data = [cash]) => ({ data, isLoading: false, error: null, refetch: vi.fn() });
it('routes existing holdings to OPENING without completing setup before a save', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: true, hasTrades: false }));
  const opening = vi.fn();
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={readyAccounts()} cashAccounts={readyCashAccounts()} onCreateAccount={() => {}} onOpening={opening}/>));
  await userEvent.click(await screen.findByRole('button', { name: '录入已有持仓' }));
  expect(opening).toHaveBeenCalledWith('3');
  expect(request.mock.calls).toHaveLength(1);
  expect(screen.getByText(/不会再次扣减现金/)).toBeInTheDocument();
});

it('hides setup when the household already has investment trades', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: true, hasTrades: true }));
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={readyAccounts()} cashAccounts={readyCashAccounts()} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/investment-setup'));
  expect(screen.queryByRole('region', { name: '投资初始化' })).not.toBeInTheDocument();
});

it('keeps setup creation actions unavailable until prerequisite accounts finish loading', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: false, hasTrades: false }));
  render(wrap(<InvestmentSetup request={request as RequestFn} manager
    accounts={{ data: undefined, isLoading: true, error: null, refetch: vi.fn() }}
    cashAccounts={readyCashAccounts()} onCreateAccount={() => {}} onOpening={() => {}}/>));
  expect(await screen.findByText('正在读取投资账户和资金账户…')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /创建投资账户|设置投资资金账户/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '暂时没有持仓，完成初始化' })).not.toBeInTheDocument();
});

it('offers an explicit prerequisite retry without treating an account error as empty data', async () => {
  const retryAccounts = vi.fn();
  const retryCashAccounts = vi.fn();
  const request = vi.fn(async () => ({ completed: false, hasAccounts: false, hasTrades: false }));
  render(wrap(<InvestmentSetup request={request as RequestFn} manager
    accounts={{ data: undefined, isLoading: false, error: new Error('账户读取失败'), refetch: retryAccounts }}
    cashAccounts={{ ...readyCashAccounts(), refetch: retryCashAccounts }} onCreateAccount={() => {}} onOpening={() => {}}/>));
  expect(await screen.findByText(/投资账户或资金账户暂时无法读取/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '重试账户数据' }));
  expect(retryAccounts).toHaveBeenCalledOnce();
  expect(retryCashAccounts).toHaveBeenCalledOnce();
  expect(screen.queryByRole('button', { name: /创建投资账户|设置投资资金账户/ })).not.toBeInTheDocument();
});

it('persists explicitly empty investment setup and hides it after confirmation', async () => {
  const request = vi.fn(async (_path: string, options?: { method?: string }) => ({ completed: options?.method === 'POST', hasAccounts: true, hasTrades: false }));
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={readyAccounts()} cashAccounts={readyCashAccounts()} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await userEvent.click(await screen.findByRole('button', { name: '暂时没有持仓，完成初始化' }));
  await waitFor(() => expect(screen.queryByRole('region', { name: '投资初始化' })).not.toBeInTheDocument());
  expect(request).toHaveBeenCalledWith('/api/investment-setup/complete', { method: 'POST' });
});

it('does not offer mutations to members or accept an uninitialized cash account', async () => {
  const request = vi.fn(async () => ({ completed: false, hasAccounts: true, hasTrades: false }));
  const view = render(wrap(<InvestmentSetup request={request as RequestFn} manager={false} accounts={readyAccounts()} cashAccounts={readyCashAccounts()} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await screen.findByText(/请家庭管理员/);
  expect(screen.queryByRole('button', { name: '录入已有持仓' })).not.toBeInTheDocument();
  view.unmount();
  render(wrap(<InvestmentSetup request={request as RequestFn} manager accounts={readyAccounts()} cashAccounts={readyCashAccounts([{ ...cash, openingConfirmed: false }])} onCreateAccount={() => {}} onOpening={() => {}}/>));
  await screen.findByRole('link', { name: '初始化现金账户' });
  expect(screen.queryByRole('button', { name: '暂时没有持仓，完成初始化' })).not.toBeInTheDocument();
});

const bar = (date: string, open: number, close: number, high: number, low: number, volume: number) => ({ timestamp: Date.parse(`${date}T00:00:00+08:00`), open, close, high, low, volume, turnover: volume * 10 });
it('aggregates weekly OHLC and sums shares and yuan across Shanghai trading dates', () => {
  const values = [bar('2026-09-04', 10, 11, 12, 9, 100), bar('2026-09-07', 11, 12, 13, 10, 200), bar('2026-09-08', 12, 10, 14, 8, 300)];
  expect(aggregateBars(values, 'week')).toEqual([values[0], { timestamp: Date.parse('2026-09-07T00:00:00+08:00'), open: 11, close: 10, high: 14, low: 8, volume: 500, turnover: 5000 }]);
  expect(values[1].volume).toBe(200);
});
it('does not mix months or years and keeps daily values unchanged', () => {
  const values = [bar('2025-12-31', 10, 11, 12, 9, 100), bar('2026-01-02', 11, 12, 13, 10, 200)];
  expect(aggregateBars(values, 'month')).toEqual(values);
  expect(aggregateBars(values, 'day')).toEqual(values);
  expect(aggregateBars([], 'week')).toEqual([]);
});

it('filters ranges only after period aggregation so boundary candles keep correct OHLC', () => {
  const values = [
    bar('2026-08-03', 10, 13, 14, 9, 100),
    bar('2026-08-31', 13, 12, 15, 11, 200),
    bar('2026-09-01', 12, 14, 16, 12, 300),
    bar('2026-09-30', 14, 15, 17, 13, 400)
  ];
  expect(selectBarsForRange(values, 'month', '1m')).toEqual([
    { timestamp: Date.parse('2026-09-01T00:00:00+08:00'), open: 12, close: 15, high: 17, low: 12, volume: 700, turnover: 7000 }
  ]);
});

const historicPosition = {
  accountId: 3, accountName: '证券账户', brokerName: '旧券商', securityId: 99, tsCode: 'HISTORIC-99', name: '历史自定义证券',
  quantity: 100, averageCost: '8.00', cost: '800.00', price: null, marketValue: '800.00', estimatedValue: '800.00',
  realizedProfit: '0.00', unrealizedProfit: null, totalProfit: '0.00', allocationPercent: '100.00', source: null, tradeDate: null,
  fetchedAt: null, stale: false, error: '未验证证券无外部行情', valuationStatus: 'COST_ESTIMATE' as const
};
const investmentRequest: RequestFn = async <T,>(path: string) => {
  if (path === '/api/portfolio') return { positions: [historicPosition], totals: { cost: '800.00', estimatedValue: '800.00', marketValue: '800.00', realizedProfit: '0.00', unrealizedProfit: null, totalProfit: '0.00', unpricedPositions: 1 } } as T;
  if (path === '/api/investment-setup') return { completed: true, hasAccounts: true, hasTrades: true } as T;
  if (path.startsWith('/api/investment-accounts')) return { items: [account], page: 0, size: 50, totalPages: 1, totalElements: 1, hasNext: false } as T;
  if (path.startsWith('/api/investment-trades')) return { items: [], page: 0, size: 50, totalPages: 0, totalElements: 0, hasNext: false } as T;
  if (path.startsWith('/api/accounts')) return { items: [cash], page: 0, size: 50, totalPages: 1, totalElements: 1, hasNext: false } as T;
  if (path === '/api/market-quotes') return [] as T;
  if (path === '/api/securities/catalog-status') return { state: 'READY', count: 5558, updatedAt: '2026-09-08T08:30:00Z' } as T;
  if (path.startsWith('/api/securities/search')) return page([]) as T;
  throw new Error(`unexpected ${path}`);
};

it('starts a locked SELL draft from a historic position without catalog verification', async () => {
  render(wrap(<InvestmentsPage request={investmentRequest} role="OWNER"/>));
  await userEvent.click(await screen.findByRole('button',{name:'历史自定义证券更多操作'}));
  await userEvent.click(await screen.findByRole('menuitem', { name: '记录卖出历史自定义证券' }));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  expect(dialog.querySelector('select[name="accountId"]')).toHaveValue('3');
  expect(screen.getByLabelText('业务类型')).toHaveValue('SELL');
  const control = within(dialog).getByRole('combobox', { name: '证券' });
  expect(control).toHaveTextContent('HISTORIC-99 · 历史自定义证券');
  expect(control).toHaveAttribute('aria-disabled', 'true');
});

it('does not expose position sale mutations to household members', async () => {
  render(wrap(<InvestmentsPage request={investmentRequest} role="MEMBER"/>));
  await screen.findAllByText('历史自定义证券');
  expect(screen.queryByRole('button', { name: /记录卖出历史自定义证券/ })).not.toBeInTheDocument();
});
