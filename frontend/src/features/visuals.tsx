import { useEffect, useId, useRef, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, ChartNoAxesCombined, CircleAlert, Landmark, ReceiptText, Wallet } from 'lucide-react';
import { money } from './common';
import type { NetWorthHistory } from '../api/contracts';

export function historyBasisLabel(row: NetWorthHistory) { return row.accountingBasis === 'LEDGER_AS_OF' ? '按生效日期重算' : row.accountingBasis === 'LEGACY' ? '历史记录（未核对）' : '统计口径待核对'; }
export function historyValuationLabel(row: NetWorthHistory) { return row.valuationEstimated ? `含成本估算（${row.unpricedPositions} 项缺价持仓）` : '按当日有效估值'; }

export function EmptyIllustration() {
  return <svg className="empty-illustration" viewBox="0 0 140 108" fill="none" aria-hidden="true"><ellipse cx="70" cy="96" rx="46" ry="7" fill="#E9EDF5"/><rect x="31" y="13" width="70" height="76" rx="9" transform="rotate(-7 31 13)" fill="#ECF0FB"/><rect x="38" y="16" width="68" height="76" rx="9" fill="white" stroke="#BECBE7"/><path d="M53 35h36M53 46h25M53 57h36" stroke="#BCC8DF" strokeWidth="3" strokeLinecap="round"/><circle cx="95" cy="79" r="16" fill="#EEF2FF" stroke="#A5B7ED"/><path d="M88 79h14m-7-7v14" stroke="#728AD2" strokeWidth="2" strokeLinecap="round"/></svg>;
}

export const entityIcons = { cash: Wallet, bank: Landmark, record: ReceiptText, income: ArrowDownLeft, expense: ArrowUpRight, chart: ChartNoAxesCombined, warning: CircleAlert };

export interface ChartPoint { label: string; income: string; expense: string }
export function FlowChart({ points, label = '收入与支出趋势', compact = false }: { points: ChartPoint[]; label?: string; compact?: boolean }) {
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(660);
  useEffect(() => {
    if (!container.current || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(entries => { if(entries[0].contentRect.width > 0) setWidth(Math.max(240,entries[0].contentRect.width)); });
    observer.observe(container.current);
    return () => observer.disconnect();
  }, []);
  const [selected, setSelected] = useState<number | null>(null);
  const active = selected === null ? null : points[selected];
  const max = Math.max(1, ...points.flatMap(p => [Number(p.income), Number(p.expense)]).filter(Number.isFinite));
  const plotHeight = 155, plotWidth = width - 60, gap = plotWidth / Math.max(points.length, 1);
  return <div className="flow-figure" ref={container}>
    <div className="chart-legend"><span><i className="income-dot"/>收入</span><span><i className="expense-dot"/>支出</span><span className="chart-selection" aria-live="polite">{active ? `${active.label} · 收入 ${money(active.income)} · 支出 ${money(active.expense)}` : compact ? '' : '点选柱形查看金额'}</span></div>
    <svg viewBox={`0 0 ${width} 215`} role="group" aria-label={label} className="flow-svg">
      {[0, .5, 1].map(r => <g key={r}><line x1="48" x2={width-12} y1={177 - plotHeight*r} y2={177 - plotHeight*r} stroke="#E7EBF0" strokeDasharray={r ? '3 5' : undefined}/><text x="39" y={181-plotHeight*r} textAnchor="end" fill="#7B8590" fontSize="10">{shortAmount(max*r)}</text></g>)}
      {points.map((p, i) => { const x = 48 + gap*i, bar = Math.min(16, gap*.28); return <g key={p.label} role="button" tabIndex={0} aria-label={`${p.label} 收入 ${money(p.income)} 支出 ${money(p.expense)}`} onFocus={() => setSelected(i)} onMouseEnter={() => setSelected(i)} onClick={() => setSelected(i)} onKeyDown={e => { if(e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(i); } }}>
        <rect x={x+1} y="12" width={Math.max(1,gap-2)} height="169" fill={selected === i ? '#F0F3FA' : 'transparent'} rx="5"/>
        <rect x={x+gap/2-bar-2} y={177-Number(p.income)/max*plotHeight} width={bar} height={Math.max(0,Number(p.income)/max*plotHeight)} rx="3" fill="#8EBBAC"/>
        <rect x={x+gap/2+2} y={177-Number(p.expense)/max*plotHeight} width={bar} height={Math.max(0,Number(p.expense)/max*plotHeight)} rx="3" fill="#526DCD"/>
        {((points.length <= 12 && (width >= 480 || points.length <= 6 || i % 2 === 1)) || (points.length > 12 && (i % 5 === 0 || i === points.length-1))) && <text x={x+gap/2} y="201" textAnchor="middle" fill="#7B8590" fontSize="10">{p.label}</text>}
      </g>; })}
    </svg>
  </div>;
}

export function HistoryChart({ data }: { data: NetWorthHistory[] }) {
  const id = useId().replace(/:/g, '');
  const sorted = [...data].filter(item=>item.netWorth!=null&&Number.isFinite(Number(item.netWorth))).sort((a,b) => a.snapshotOn.localeCompare(b.snapshotOn));
  if (!sorted.length) return <p className="muted">历史记录正在积累</p>;
  const values = sorted.map(p => Number(p.netWorth));
  const lo = Math.min(0,...values), hi = Math.max(1,...values), range = hi-lo;
  const xy = sorted.map((p,i) => [20+i*600/Math.max(1,sorted.length-1), 116-(Number(p.netWorth)-lo)/range*90]);
  const line = xy.map(([x,y],i) => `${i?'L':'M'}${x},${y}`).join(' ');
  const zero = 116-(0-lo)/range*90;
  return <div className="history-figure"><svg viewBox="0 0 640 143" role="img" aria-label="净资产历史趋势，按日期从早到晚"><defs><linearGradient id={id} x1="0" y1="0" x2="0" y2="1"><stop stopColor="#768BD6" stopOpacity=".19"/><stop offset="1" stopColor="#768BD6" stopOpacity=".01"/></linearGradient></defs><path d={`${line} L${xy.at(-1)![0]},${zero} L20,${zero} Z`} fill={`url(#${id})`}/><line x1="20" x2="620" y1={zero} y2={zero} stroke="#DFE5F0"/><path d={line} stroke="#617BC6" strokeWidth="2.2" fill="none" strokeLinejoin="round"/>{xy.map(([x,y],i)=><circle key={i} cx={x} cy={y} r="3" fill="#617BC6"><title>{sorted[i].snapshotOn}：{money(sorted[i].netWorth)} · {historyValuationLabel(sorted[i])} · {historyBasisLabel(sorted[i])}</title></circle>)}<text x="20" y="139" fill="#798391" fontSize="10">{sorted[0].snapshotOn}</text><text x="620" y="139" textAnchor="end" fill="#798391" fontSize="10">{sorted.at(-1)!.snapshotOn}</text></svg></div>;
}
function shortAmount(v: number) { return v >= 100000000 ? `${(v/100000000).toFixed(1)}亿` : v >= 10000 ? `${(v/10000).toFixed(1)}万` : Math.round(v).toLocaleString('zh-CN'); }
