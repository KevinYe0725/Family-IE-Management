import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, ArrowUpRight, ChartNoAxesCombined } from 'lucide-react';
import type { Portfolio, PortfolioPosition } from '../../api/contracts';
import { ActionDialog, QueryState, money, type RequestFn } from '../common';
import { StockChart } from '../investment/StockChart';

const finite = (value: string | null | undefined) => value != null && Number.isFinite(Number(value)) ? Number(value) : null;
const tone = (value: string | null | undefined) => value == null ? '' : Number(value) > 0 ? 'is-gain' : Number(value) < 0 ? 'is-loss' : '';
function baseValue(position: PortfolioPosition) {
  return position.base ? finite(position.base.marketValue) : (position.currency ?? 'CNY') === 'CNY' ? finite(position.marketValue) : null;
}

export function HomePortfolio({ request, stale }: { request: RequestFn; stale: boolean }) {
  const portfolio = useQuery({ queryKey: ['portfolio'], queryFn: () => request<Portfolio>('/api/portfolio') });
  const [selected, setSelected] = useState<PortfolioPosition | null>(null);
  const positions = (portfolio.data?.positions ?? []).filter(row => row.quantity > 0);
  const sorted = [...positions].sort((a,b) => (baseValue(b) ?? -1) - (baseValue(a) ?? -1));
  // Only compare values already converted to the same currency. Never invent FX or quote history.
  const complete = positions.every(row => baseValue(row) !== null);
  const total = complete ? positions.reduce((sum,row) => sum + (baseValue(row) ?? 0), 0) : 0;
  const totals = portfolio.data?.totals;
  return <section className="home-investments home-surface" aria-label="我的持仓">
    <header className="home-section-heading"><h2><ChartNoAxesCombined size={20} aria-hidden="true"/>我的持仓</h2><a className="home-link" href="/workspace/investments">全部持仓<ArrowRight size={17}/></a></header>
    <QueryState loading={portfolio.isLoading} error={portfolio.error}>
      <div className="home-portfolio-metrics"><div><span>持仓市值</span><strong>{money(totals?.marketValue, totals?.currency ?? 'CNY')}</strong></div><div><span>持仓盈亏</span><strong className={tone(totals?.unrealizedProfit)}>{money(totals?.unrealizedProfit, totals?.currency ?? 'CNY')}</strong></div><div className="home-quote-status">
        {totals?.unpricedPositions ? <span>缺价待补齐</span> : stale ? <span>行情已过期</span> : null}
        {!!totals?.missingFxRates && <a href="/workspace/investments?tab=rates">汇率待补齐</a>}
      </div></div>
      {positions.length ? <>
        <div className="home-stock-head" aria-hidden="true"><span>股票</span><span>参考价格</span><span>持仓盈亏</span><span>持仓占比</span></div>
        <div className="home-stock-list">{sorted.slice(0,5).map(row => {
          const foreign = row.market === 'HK' || row.market === 'US';
          const market = row.market === 'HK' ? '港股' : row.market === 'US' ? '美股' : 'A 股';
          const weight = total > 0 ? Math.max(0,Math.min(100,(baseValue(row) ?? 0)/total*100)) : null;
          const priceState = row.price == null ? '缺少价格' : row.error ? '报价待更新' : row.stale ? '报价已过期' : row.source === 'MANUAL' ? '手工价格' : '参考报价';
          return <button className="home-stock-row" key={`${row.accountId}-${row.securityId}`} aria-label={`查看${row.name}行情`} disabled={foreign && !row.symbol} onClick={() => setSelected(row)} title={`${row.accountName} · ${priceState}${row.tradeDate ? ' · '+row.tradeDate : ''}`}>
            <span className="home-stock-identity"><span className={`home-stock-emblem market-${row.market ?? 'CN'}`} aria-hidden="true">{(row.symbol ?? row.tsCode).replace(/[^A-Za-z]/g,'').slice(0,2) || row.name.slice(0,1)}</span><span><strong>{row.name}</strong><span className="home-stock-code">{row.symbol ?? row.tsCode} · {market}</span></span></span>
            <span className="home-stock-price"><strong>{money(row.price, row.currency ?? 'CNY')}</strong>{(row.price == null || row.stale || row.error || row.source === 'MANUAL') && <span>{priceState}</span>}</span>
            <span className={`home-stock-profit ${tone(row.unrealizedProfit)}`}>{money(row.unrealizedProfit, row.currency ?? 'CNY')}</span>
            <span className="home-stock-weight" aria-label={weight == null ? '持仓占比待补齐估值' : `持仓占比 ${weight.toFixed(1)}%`}><span className="home-weight-track" aria-hidden="true"><i style={{width:`${weight ?? 0}%`}}/></span><span>{weight == null ? '—' : `${weight.toFixed(1)}%`}</span><ArrowUpRight size={16} aria-hidden="true"/></span>
          </button>;
        })}</div>
      </> : <div className="home-empty-investments"><ChartNoAxesCombined size={32} aria-hidden="true"/><p>还没有投资持仓</p><a className="home-link" href="/workspace/investments">记录第一笔投资<ArrowRight size={16}/></a></div>}
    </QueryState>
    {selected && <ActionDialog open title={`${selected.name} · 行情`} size="wide" onClose={() => setSelected(null)}><StockChart request={request} security={{id:selected.securityId,tsCode:selected.tsCode,name:selected.name,market:selected.market,symbol:selected.symbol,currency:selected.currency,exchange:selected.exchange,timezone:selected.timezone}}/></ActionDialog>}
  </section>;
}
