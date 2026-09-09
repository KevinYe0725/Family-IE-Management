import {useState,type ReactNode} from 'react';
import { CircleAlert, CircleHelp, Eye, EyeOff } from 'lucide-react';
import type { Portfolio } from '../../api/contracts';
import { money } from '../common';

function MetricHelp({ label, children }: { label: string; children: ReactNode }) {
  return <details className="metric-help" onKeyDown={event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.currentTarget.open = false;
      event.currentTarget.querySelector('summary')?.focus();
    }
  }}>
    <summary aria-label={label}><CircleHelp size={15} aria-hidden="true"/></summary>
    <div className="metric-help__content">{children}</div>
  </details>;
}

export function PortfolioSummary({ portfolio, onViewQuotes, onManageRates }: { portfolio?: Portfolio; onViewQuotes: () => void; onManageRates?:()=>void }) {
  const rateAction=(label:string)=>onManageRates?<button type="button" onClick={onManageRates}>{label}</button>:<a href="/workspace/investments?tab=rates">{label}</a>;
  const [hidden,setHidden]=useState(false);
  const displayMoney=(value:string|number|null|undefined)=>hidden?'••••':money(value);
  const totals = portfolio?.totals;
  const unpriced = totals?.unpricedPositions ?? 0;
  const missingHistoricalFx=portfolio?.positions.some(item=>item.currency!=='CNY'&&item.base&&(item.base.cost==null||item.base.realizedProfit==null));
  return <div className="investment-hero">
    <div className="summary-strip investment-summary">
      <div>
        <div className="investment-summary__label">组合市值<button className="investment-eye" aria-label={hidden?"显示汇总金额":"隐藏汇总金额"} aria-pressed={hidden} onClick={()=>setHidden(!hidden)}>{hidden?<EyeOff size={18}/>:<Eye size={18}/>}</button><span className="investment-unit">人民币 CNY</span><MetricHelp label="市值口径">按有效行情计算。缺价持仓保留成本估算，组合市值与浮动收益暂未知。</MetricHelp></div>
        <strong>{displayMoney(totals?.marketValue)}</strong>{unpriced > 0 && <small>含成本估算的组合价值 {displayMoney(totals?.estimatedValue)}</small>}
      </div>
      <div>
        <div className="investment-summary__label">累计收益<span className="investment-unit">人民币 CNY</span><MetricHelp label="收益口径">按人民币展示，使用历史买入成本；包含价格和汇率变化的影响。</MetricHelp></div>
        <strong className={totals?.totalProfit==null?'':Number(totals.totalProfit)>=0?'positive':'negative'}>{displayMoney(totals?.totalProfit)}</strong>
      </div>
    </div>
    {(missingHistoricalFx||Boolean(totals?.missingFxRates)||unpriced>0)&&<div className="portfolio-warning" role="status">
    {missingHistoricalFx&&<div className="portfolio-warning-item"><span>缺少交易发生日汇率，人民币成本或收益待补充。原币记录已保留。</span>{rateAction('补充历史汇率')}</div>}
    {Boolean(totals?.missingFxRates)&&<div className="portfolio-warning-item"><span>{totals?.missingFxRates} 项持仓缺少汇率，已折算小计 {displayMoney(totals?.knownEstimatedValue)}。</span>{rateAction('补充汇率')}</div>}
    {unpriced > 0 && <div className="portfolio-warning-item">
      <CircleAlert size={17} aria-hidden="true"/>
      <span>{unpriced} 项持仓缺少价格，市值与浮动收益尚不完整。</span>
      <button type="button" onClick={onViewQuotes}>查看行情</button>
    </div>}
    </div>}
  </div>;
}
