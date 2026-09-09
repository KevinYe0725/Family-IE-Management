import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StockChart } from './StockChart';
import { ReferenceQuote } from './quote-experience';
import type { RequestFn } from '../common';

const chart = vi.hoisted(() => ({ setSymbol: vi.fn(), setPeriod: vi.fn(), setDataLoader: vi.fn(), createIndicator: vi.fn(), setBarSpace: vi.fn(), scrollToRealTime: vi.fn(), resize: vi.fn() }));
const dispose = vi.hoisted(() => vi.fn());
const init = vi.hoisted(() => vi.fn((): typeof chart | null => chart));
vi.mock('klinecharts', () => ({ init, dispose }));
const stock = { id: 5, tsCode: '000001.SZ', name: '平安银行' };
function show(request: RequestFn, symbol = stock) { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><StockChart request={request} security={symbol}/></QueryClientProvider>); }
const bar = (date: string, open: number, close: number, high: number, low: number, volume: number) => ({ timestamp: Date.parse(`${date}T00:00:00+08:00`), open, close, high, low, volume, turnover: volume * 10 });
const data = { symbol: '000001.SZ', source: 'BAOSTOCK', adjustment: 'qfq', asOf: '2026-09-30', fetchedAt: '2026-09-30T08:00:00Z', stale: false, supported: true, bars: [bar('2025-08-01', 9, 10, 11, 8, 50), bar('2026-08-03', 10, 13, 14, 9, 100), bar('2026-08-31', 13, 12, 15, 11, 200), bar('2026-09-01', 12, 14, 16, 12, 300), bar('2026-09-30', 14, 15, 17, 13, 400)] };
beforeEach(() => {
  init.mockReset();
  init.mockReturnValue(chart);
  dispose.mockClear();
  Object.values(chart).forEach(mock => mock.mockClear());
});
it('shows a timestamped delayed spot quote separately from daily candles and names its comparison date',async()=>{
 const request=(async()=>({...data,adjustment:'none'})) as RequestFn;
 render(<QueryClientProvider client={new QueryClient()}><StockChart request={request} security={stock} compact liveQuote={{securityId:5,price:'18.00',currency:'CNY',source:'TENCENT_PUBLIC',quotedAt:'2026-09-30T06:00:00Z',fetchedAt:'2026-09-30T06:01:00Z',status:'DELAYED',marketState:'OPEN',ageSeconds:60,delayMinutes:15}}/></QueryClientProvider>);
 expect(screen.getByText('¥18.00')).toBeInTheDocument();expect(screen.getByText('延迟报价')).toBeInTheDocument();
 expect(await screen.findByText('+28.57% · 较 2026-09-01 收盘')).toBeInTheDocument();
 expect(screen.queryByLabelText('复权方式')).not.toBeInTheDocument();
 expect(screen.queryByText('今日涨跌')).not.toBeInTheDocument();
});
it('shares one unadjusted request between reference price and initial chart instead of racing the adapter', async () => {
  const request = vi.fn(async (path: string) => ({...data, adjustment: path.endsWith('none') ? 'none' : 'qfq'}));
  render(<QueryClientProvider client={new QueryClient({defaultOptions: {queries: {retry: false}}})}><ReferenceQuote request={request as RequestFn} security={stock}/><StockChart request={request as RequestFn} security={stock}/></QueryClientProvider>);
  await waitFor(() => expect(chart.setDataLoader).toHaveBeenCalled());
  expect(await screen.findByRole('complementary', {name: '参考报价'})).toHaveTextContent('¥15.00');
  expect(request.mock.calls.filter(([path])=>path.includes('/candles?'))).toHaveLength(1);
  expect(request).toHaveBeenCalledWith('/api/securities/5/candles?adjust=none');
  await userEvent.selectOptions(screen.getByLabelText('复权方式'), 'qfq');
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/securities/5/candles?adjust=qfq'));
});
it('loads selected stock candles, hands actual bars to KLineChart, and disposes on close', async () => {
  const request = vi.fn(async () => data);
  const view = show(request as RequestFn);
  await waitFor(() => expect(chart.setDataLoader).toHaveBeenCalled());
  const loader = chart.setDataLoader.mock.calls.at(-1)![0];
  const callback = vi.fn();
  loader.getBars({ type: 'init', callback });
  expect(callback).toHaveBeenCalledWith(data.bars, false);
  expect(screen.getByText(/BaoStock/)).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText('复权方式'), 'none');
  await waitFor(() => expect(request).toHaveBeenCalledWith('/api/securities/5/candles?adjust=none'));
  view.unmount();
  expect(dispose).toHaveBeenCalled();
});
it('offers bounded history ranges and labels period-specific latest data', async () => {
  show((async () => data) as RequestFn);
  await waitFor(() => expect(chart.setDataLoader).toHaveBeenCalled());
  expect(screen.getByLabelText('视窗缩放')).toHaveValue('all');
  expect(screen.getByText(/最多约 2 年/)).toBeInTheDocument();
  expect(screen.getByText('按所选时间跨度缩放，可拖动查看更早历史。')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '月 K' }));
  await userEvent.selectOptions(screen.getByLabelText('视窗缩放'), '1m');
  await waitFor(() => expect(screen.getByText('查看最近一月数据')).toBeInTheDocument());
  const loader = chart.setDataLoader.mock.calls.at(-1)![0];
  const callback = vi.fn();
  loader.getBars({ type: 'init', callback });
  expect(callback).toHaveBeenCalledWith([
    data.bars[0],
    { timestamp: Date.parse('2026-08-03T00:00:00+08:00'), open: 10, close: 12, high: 15, low: 9, volume: 300, turnover: 3000 },
    { timestamp: Date.parse('2026-09-01T00:00:00+08:00'), open: 12, close: 15, high: 17, low: 12, volume: 700, turnover: 7000 }
  ], false);
  expect(screen.getByText(/月 K 的首尾周期可能不完整/)).toBeInTheDocument();
});
it('keeps full indicator warmup history while a short range changes the viewport', async () => {
  const start = Date.parse('2026-06-01T00:00:00+08:00');
  const bars = Array.from({ length: 100 }, (_, index) => ({ timestamp: start + index * 86400_000, open: 10 + index, close: 11 + index, high: 12 + index, low: 9 + index, volume: 100 + index, turnover: (100 + index) * 10 }));
  show((async () => ({ ...data, bars })) as RequestFn);
  await waitFor(() => expect(chart.setBarSpace).toHaveBeenCalled());
  const allSpace = chart.setBarSpace.mock.calls.at(-1)![0];
  await userEvent.selectOptions(screen.getByLabelText('视窗缩放'), '1m');
  await waitFor(() => expect(chart.setBarSpace.mock.calls.at(-1)![0]).toBeGreaterThan(allSpace));
  const loader = chart.setDataLoader.mock.calls.at(-1)![0];
  const callback = vi.fn();
  loader.getBars({ type: 'init', callback });
  expect(callback).toHaveBeenCalledWith(bars, false);
  expect(chart.scrollToRealTime).toHaveBeenCalled();
});
it('uses red-up and green-down styles for volume and MACD indicators', async () => {
  show((async () => data) as RequestFn);
  await waitFor(() => expect(chart.createIndicator).toHaveBeenCalled());
  await userEvent.click(screen.getByRole('checkbox', { name: 'MACD' }));
  await waitFor(() => expect(chart.createIndicator.mock.calls.some(([value]) => typeof value === 'object' && value.name === 'MACD')).toBe(true));
  for (const name of ['VOL', 'MACD']) {
    const value = chart.createIndicator.mock.calls.map(([item]) => item).find(item => typeof item === 'object' && item.name === name);
    expect(value).toEqual(expect.objectContaining({ styles: { bars: [{ upColor: '#c74b50', downColor: '#31846a', noChangeColor: '#67727e' }] } }));
  }
});
it('shows explicit unsupported coverage rather than a fake or empty chart', async () => {
  show((async () => ({ ...data, supported: false, bars: [] })) as RequestFn, { id: 8, name: '万达轴承', tsCode: '920002.BJ' });
  expect(await screen.findByText(/暂未覆盖这只股票/)).toBeInTheDocument();
  expect(screen.queryByRole('img', { name: /K 线图/ })).not.toBeInTheDocument();
});
it('keeps stale history visibly identified and offers retry after failure', async () => {
  let fail = true;
  show((async () => { if (fail) throw new Error('行情服务暂时不可用'); return { ...data, stale: true }; }) as RequestFn);
  await screen.findByText('行情服务暂时不可用');
  fail = false;
  await userEvent.click(screen.getByRole('button', { name: '重新加载行情' }));
  expect(await screen.findByText(/缓存行情/)).toBeInTheDocument();
});
it('removes the image role when chart initialization fails', async () => {
  init.mockReturnValue(null);
  show((async () => data) as RequestFn);
  expect(await screen.findByRole('alert')).toHaveTextContent('图表暂时无法绘制');
  expect(screen.queryByRole('img', { name: /K 线图/ })).not.toBeInTheDocument();
});
