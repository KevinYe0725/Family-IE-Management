import {quoteTime,type LiveQuote} from './live-quotes';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { aggregateBars, selectBarsForRange, type CandleBar, type CandleResponse, type ChartPeriod, type ChartRange } from './chart-data';
import { dateText, money, type RequestFn } from '../common';
import { isOverseasInstrument, marketMoney, type OverseasInstrument, type OverseasCandles } from './overseas-market';

export type ChartSecurity = { id: number; tsCode: string; name: string; market?:string;symbol?:string;currency?:string;exchange?:string;timezone?:string };
export function StockChart({ request, security,compact=false,liveQuote }: { request: RequestFn; security: ChartSecurity | OverseasInstrument;compact?:boolean;liveQuote?:LiveQuote }) {
  const foreign = security.market==='HK'||security.market==='US';
  const registered='id' in security?security:undefined;
  const market=security.market as 'HK'|'US';
  const currency=security.currency??'CNY';
  const symbol = foreign ? security.symbol??'' : registered?.tsCode??'';
  const timezone = foreign ? security.timezone??'Asia/Shanghai' : 'Asia/Shanghai';
  const formatPrice = (value: string | number | null | undefined) => foreign ? marketMoney(value, currency) : money(value);
  // Share the initial request with ReferenceQuote; the adapter serializes provider access.
  // Adjusted history remains an explicit chart-only choice, never a transaction price.
  const [adjustment, setAdjustment] = useState<'none' | 'qfq'>('none');
  const [period, setPeriod] = useState<ChartPeriod>('day');
  const [range, setRange] = useState<ChartRange>('all');
  const [macd, setMacd] = useState(false);
  const effectiveAdjustment = foreign || compact ? 'none' : adjustment;
  const query = useQuery({ queryKey: foreign ? ['overseas-candles', security.market, symbol] : ['security-candles', registered?.id, effectiveAdjustment], queryFn: async (): Promise<CandleResponse> => {
    if (!foreign) return request<CandleResponse>(`/api/securities/${registered?.id}/candles?adjust=${effectiveAdjustment}`);
    const value = await request<OverseasCandles>(`/api/overseas-market/candles?market=${security.market}&symbol=${encodeURIComponent(symbol)}`);
    if (!value || !isOverseasInstrument(value.instrument, market) || value.instrument.symbol !== symbol
      || value.instrument.currency !== currency || value.symbol !== symbol || value.source !== 'SINA' || value.adjustment !== 'none'
      || !Array.isArray(value.bars) || typeof value.supported !== 'boolean') throw new Error('行情响应与所选股票不一致');
    return value;
  }, staleTime: 300_000, retry: false, refetchInterval:compact?300_000:false,refetchIntervalInBackground:false });
  const periodBars = useMemo(() => aggregateBars(query.data?.bars ?? [], period, timezone), [query.data, period, timezone]);
  const visibleBars = useMemo(() => selectBarsForRange(query.data?.bars ?? [], period, range, timezone), [query.data, period, range, timezone]);
  const last = visibleBars.at(-1);
  const livePrice=liveQuote?.price&&liveQuote.status!=='UNAVAILABLE'?liveQuote.price:null;
  const quoteDate=liveQuote?.quotedAt?new Date(liveQuote.quotedAt).getTime():NaN;
  const day=(t:number)=>new Intl.DateTimeFormat('en-CA',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit'}).format(t);
  const baseline=Number.isFinite(quoteDate)&&effectiveAdjustment==='none'?(query.data?.bars??[]).filter(b=>day(b.timestamp)<day(quoteDate)).at(-1):undefined;
  const change=livePrice&&baseline&&baseline.close>0?(Number(livePrice)/baseline.close-1)*100:null;
  const periodName = ({ day: '交易日', week: '一周', month: '一月' } as const)[period];
  const rangeName = ({ '1m': '最近 1 个月', '3m': '最近 3 个月', '1y': '最近 1 年', all: '返回的全部历史' } as const)[range];
  return <section className="stock-chart">
    <header className="stock-chart-heading"><div><span>{foreign ? `${security.exchange} · ${symbol}` : symbol}</span><h3>{security.name}</h3></div><div className="stock-chart-price">{(foreign||compact) && <span>{livePrice?(liveQuote?.status==='DELAYED'?'延迟报价':liveQuote?.status==='STALE'?'缓存报价':'盘中参考价'):'参考收盘价'}</span>}<strong>{formatPrice(livePrice??last?.close)}</strong>{compact&&change!=null&&<span className={`pinned-stock-change ${change>0?'is-gain':change<0?'is-loss':''}`}>{change>0?'+':''}{change.toFixed(2)}% · 较 {day(baseline!.timestamp)} 收盘</span>}</div></header>
    <div className="stock-chart-controls"><div className="segmented-tabs" aria-label="K 线周期">{(['day', 'week', 'month'] as const).map(value => <button key={value} type="button" aria-pressed={period === value} className={period === value ? 'active' : ''} onClick={() => setPeriod(value)}>{({ day: '日 K', week: '周 K', month: '月 K' })[value]}</button>)}</div>
      {!foreign && !compact && <label>复权方式<select value={adjustment} onChange={event => setAdjustment(event.target.value as 'none' | 'qfq')}><option value="qfq">前复权</option><option value="none">不复权</option></select></label>}
      <label>视窗缩放<select value={range} onChange={event => setRange(event.target.value as ChartRange)}><option value="1m">最近 1 个月</option><option value="3m">最近 3 个月</option><option value="1y">最近 1 年</option><option value="all">全部</option></select></label>
      <label className="stock-chart-indicator"><input type="checkbox" checked={macd} onChange={event => setMacd(event.target.checked)}/>MACD</label>
    </div>
    {compact&&livePrice?<p className="pinned-stock-status">报价时间 {quoteTime(liveQuote?.quotedAt)}</p>:!compact&&<p className="stock-chart-range-note">按所选时间跨度缩放，可拖动查看更早历史。</p>}
    {query.isLoading ? <div role="status" className="stock-chart-message">正在加载历史行情…</div> : query.error ? <div role="alert" className="stock-chart-message"><p>{query.error instanceof Error ? query.error.message : '行情暂时不可用'}</p><button type="button" onClick={() => { void query.refetch(); }}>重新加载行情</button></div> : !query.data?.supported ? <div role="status" className="stock-chart-message">行情源暂未覆盖这只股票。已有持仓和交易记录仍会保留。</div> : !visibleBars.length ? <div role="status" className="stock-chart-message">所选范围内没有可用的历史行情。</div> : <>
      <div className="stock-chart-provenance" role="status"><span>{query.data.source === 'BAOSTOCK' ? 'BaoStock' : query.data.source === 'SINA' ? '新浪财经' : query.data.source} · 截至 {dateText(query.data.asOf)}{!compact&&<> · {rangeName}（返回数据最多约 2 年）</>}</span>{query.data.stale && <strong>缓存行情，尚未更新到最近交易日</strong>}</div>
      <CandleCanvas bars={periodBars} visibleCount={visibleBars.length} security={{ tsCode: symbol, name: security.name }} timezone={timezone} period={period} macd={macd}/>
      {!compact&&<div className="stock-chart-footnote"><span>红涨绿跌 · 成交量：股 · 仅收盘数据</span><span>{effectiveAdjustment === 'qfq' ? '前复权用于走势展示，不改变实际成本或估值。' : '不复权为实际历史价格。'}{period !== 'day' && ` ${period === 'week' ? '周 K' : '月 K'} 的首尾周期可能不完整（行情返回边界或当前周期）。`}</span></div>}
      <details className="stock-chart-details"><summary>查看最近{periodName}数据</summary><dl><div><dt>开盘</dt><dd>{formatPrice(last?.open)}</dd></div><div><dt>最高</dt><dd>{formatPrice(last?.high)}</dd></div><div><dt>最低</dt><dd>{formatPrice(last?.low)}</dd></div><div><dt>成交量</dt><dd>{last?.volume.toLocaleString('zh-CN')} 股</dd></div></dl></details>
    </>}
  </section>;
}

function CandleCanvas({ bars, visibleCount, security, timezone, period, macd }: { bars: CandleBar[]; visibleCount: number; security: Pick<ChartSecurity, 'tsCode' | 'name'>; timezone: string; period: ChartPeriod; macd: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const element = host.current;
    if (!element) return;
    let cancelled = false;
    let cleanup: (() => void) | undefined;
    setFailed(false);
    void import('klinecharts').then(({ init, dispose }) => {
      if (cancelled) return;
      const chart = init(element, { locale: 'zh-CN', timezone, styles: { candle: { bar: { upColor: '#c74b50', downColor: '#31846a', noChangeColor: '#67727e', upBorderColor: '#c74b50', downBorderColor: '#31846a', upWickColor: '#c74b50', downWickColor: '#31846a' } } } });
      if (!chart) { setFailed(true); return; }
      cleanup = () => dispose(element);
      chart.setSymbol({ ticker: security.tsCode, pricePrecision: 2, volumePrecision: 0 });
      chart.setPeriod({ span: 1, type: period });
      chart.setDataLoader({ getBars: ({ type, callback }) => callback(type === 'init' ? bars.map(bar => ({ ...bar, turnover: bar.turnover ?? undefined })) : [], false) });
      chart.createIndicator({ name: 'MA', paneId: 'candle_pane' });
      const indicatorStyles = { bars: [{ upColor: '#c74b50', downColor: '#31846a', noChangeColor: '#67727e' }] };
      chart.createIndicator({ name: 'VOL', styles: indicatorStyles });
      if (macd) chart.createIndicator({ name: 'MACD', styles: indicatorStyles });
      const frameRange = () => {
        chart.resize();
        const drawableWidth = Math.max((element.clientWidth || 960) - 96, 240);
        chart.setBarSpace(Math.min(50, Math.max(1, drawableWidth / Math.max(visibleCount, 1))));
        chart.scrollToRealTime();
      };
      const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(frameRange) : null;
      observer?.observe(element);
      frameRange();
      cleanup = () => { observer?.disconnect(); dispose(element); };
    }).catch(() => { cleanup?.(); cleanup = undefined; if (!cancelled) setFailed(true); });
    return () => { cancelled = true; cleanup?.(); };
  }, [bars, visibleCount, security.tsCode, timezone, period, macd]);
  return <>{failed && <p role="alert">图表暂时无法绘制，可展开下方查看价格数据。</p>}<div ref={host} className="stock-chart-canvas" role={failed ? undefined : 'img'} aria-label={failed ? undefined : `${security.name} K 线图，可缩放和拖动`}/></>;
}
