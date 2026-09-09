import {useQuery} from '@tanstack/react-query';
import type {Category,TransactionKind} from '../../api/contracts';
import {Info,X} from 'lucide-react';
import {QueryState,money,type RequestFn} from '../common';

type CategoryTotal={categoryId:number;name:string;color:string;kind:TransactionKind;amount:string|null;count:number};
type DailyTotal={date:string;kind:TransactionKind;categoryId:number;amount:string|null;count:number};
export interface LedgerSummary {currency:string;income:string|null;expense:string|null;balance:string|null;transactionCount:number;unconvertedCount:number;nonCashTransactionCount?:number;categories:CategoryTotal[];daily:DailyTotal[]}
const palette=['#526DCD','#3B8A7A','#D68A55','#9B78BB','#C65F75','#6195B0','#A59B51','#668F61','#BC7860','#6882A0','#B68AAB','#5B9B98'];
const cents=(v:string|null)=>{if(v==null)return 0n;const [whole,fraction='']=v.split('.');return BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0').slice(0,2));};
export function LedgerCharts({request,filters,kind,categories=[],selectedDay,onKind,onCategory,onDay}:{request:RequestFn;filters:string;kind:TransactionKind;categories?:Category[];selectedDay:string|null;onKind:(kind:TransactionKind)=>void;onCategory:(id:number|null)=>void;onDay:(day:string|null)=>void}){
 const query=useQuery({queryKey:['transactions','summary',filters],queryFn:async()=>{
  const data=await request<LedgerSummary>(`/api/transactions/summary?${filters}`);
  if(!data||!Array.isArray(data.categories)||!Array.isArray(data.daily)||typeof data.unconvertedCount!=='number')throw new Error('收支统计暂时不可用');
  return data;
 },retry:false});
 const data=query.data, label=kind==='income'?'收入':'支出';
 const rows=(data?.categories??[]).filter(row=>row.kind===kind&&row.amount!=null&&cents(row.amount)>0n).sort((a,b)=>Number(cents(b.amount)-cents(a.amount)));
 const used=new Set<string>(),colors=new Map<number,string>();
 for(const row of [...(categories.length?categories:rows)].sort((a,b)=>('id' in a?a.id:a.categoryId)-('id' in b?b.id:b.categoryId))){
  const id='id' in row?row.id:row.categoryId;
  const preferred=/^#[0-9a-f]{6}$/i.test(row.color)?row.color:palette[id%palette.length];
  const color=!used.has(preferred)?preferred:palette.find(c=>!used.has(c))??palette[id%palette.length];
  colors.set(id,color);used.add(color);
 }
 const total=rows.reduce((sum,row)=>sum+cents(row.amount),0n), circumference=2*Math.PI*77;
 let offset=0;
 const ring=rows.map((row,index)=>{const share=total?Number(cents(row.amount))*100/Number(total):0;const entry={...row,share,offset,color:index<5?colors.get(row.categoryId):'#AAB3C4'};offset+=share;return entry;});
 const topIds=new Set(rows.slice(0,5).map(row=>row.categoryId));
 const daily=(data?.daily??[]).filter(row=>row.kind===kind&&row.amount!=null);
 const dates=[...new Set(daily.map(row=>row.date))].sort();
 const values=dates.map(date=>daily.filter(row=>row.date===date));
 const maximum=Math.max(1,...values.map(entries=>Number(entries.reduce((sum,row)=>sum+cents(row.amount),0n))));
 return <section className="ledger-charts" aria-label="收支统计">
  <header className="ledger-chart-heading"><div className="segmented-tabs" aria-label="图表收支类型"><button type="button" aria-pressed={kind==='expense'} className={kind==='expense'?'active':''} onClick={()=>onKind('expense')}>支出</button><button type="button" aria-pressed={kind==='income'} className={kind==='income'?'active':''} onClick={()=>onKind('income')}>收入</button></div><span title="按筛选后的现金收支统计，包含账户实际支付的还款本金，排除买方直接代偿；外币按入账时的历史汇率折算为人民币。"><Info size={17} aria-label="统计口径"/></span></header>
  <QueryState loading={query.isLoading} error={query.error}>
   <div className="ledger-summary-values"><div><span>收入</span><strong>{money(data?.income)}</strong></div><div><span>支出</span><strong>{money(data?.expense)}</strong></div><div><span>收支差额</span><strong>{money(data?.balance)}</strong></div></div>
   {(data?.nonCashTransactionCount??0)>0&&<p role="status">{data?.nonCashTransactionCount} 笔买方代偿未计入现金收支</p>}
   {!!data?.unconvertedCount?<div role="status" className="ledger-fx-state">{data.unconvertedCount} 笔流水待补充历史汇率 <a href="/workspace/investments?tab=rates">补充汇率</a></div>:rows.length?<div className="ledger-chart-grid">
    <div className="ledger-category-panel"><h2>{label}分类</h2><div className="ledger-category-body"><svg className="ledger-donut" viewBox="0 0 210 210" role="img" aria-label={`${label}分类：${ring.map(row=>row.name+' '+row.share.toFixed(1)+'%').join('，')}`}><circle cx="105" cy="105" r="77" fill="none" stroke="#edf0f6" strokeWidth="26"/>{ring.map(row=><circle key={row.categoryId} cx="105" cy="105" r="77" fill="none" stroke={row.color} strokeWidth="26" strokeDasharray={`${row.share/100*circumference} ${circumference}`} strokeDashoffset={-row.offset/100*circumference} transform="rotate(-90 105 105)" onClick={()=>onCategory(row.categoryId)}><title>{row.name}：{money(row.amount)} · {row.share.toFixed(1)}%</title></circle>)}<text x="105" y="98" textAnchor="middle" className="ledger-donut-label">{label}</text><text x="105" y="124" textAnchor="middle" className="ledger-donut-total">{money(kind==='income'?data?.income:data?.expense)}</text></svg><div className="ledger-category-legend">{ring.slice(0,5).map(row=><button type="button" key={row.categoryId} aria-label={`筛选${row.name}`} onClick={()=>onCategory(row.categoryId)}><i style={{background:row.color}}/><span>{row.name}</span><b>{row.share.toFixed(1)}%</b></button>)}{ring.length>5&&<details><summary>其他 {ring.length-5} 类</summary>{ring.slice(5).map(row=><button type="button" key={row.categoryId} onClick={()=>onCategory(row.categoryId)}>{row.name}<b>{row.share.toFixed(1)}%</b></button>)}</details>}</div></div></div>
    <div className="ledger-bars-panel"><h2>每日{label}</h2><div className="ledger-bars-scroll"><svg viewBox="0 0 660 230" className="ledger-stacked-bars" role="group" aria-label="每日分类收支">{[0,.5,1].map(r=><g key={r}><line x1="48" x2="646" y1={188-160*r} y2={188-160*r} stroke="#e7ebf1" strokeDasharray={r?'3 5':undefined}/><text x="40" y={192-160*r} textAnchor="end">{(maximum/100*r).toLocaleString('zh-CN',{maximumFractionDigits:0})}</text></g>)}{dates.map((date,i)=>{const width=580/Math.max(1,dates.length),x=55+i*width;let height=0;return <g key={date} role="button" tabIndex={0} aria-label={`筛选${date}${label}`} onClick={()=>onDay(date)} onKeyDown={e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();onDay(date);}}}>{values[i].map(row=>{const h=Number(cents(row.amount))/maximum*160,y=188-height-h;height+=h;return <rect key={row.categoryId} x={x+2} y={y} width={Math.max(2,width-5)} height={h} fill={topIds.has(row.categoryId)?colors.get(row.categoryId):'#AAB3C4'}><title>{date} · {rows.find(c=>c.categoryId===row.categoryId)?.name}：{money(row.amount)}</title></rect>;})}{(dates.length<16||i%5===0||i===dates.length-1)&&<text x={x+width/2} y="212" textAnchor="middle">{date.slice(8)}日</text>}</g>;})}</svg></div></div>
   </div>:<p className="ledger-chart-empty">当前筛选下暂无{label}</p>}
  </QueryState>
  {(selectedDay||new URLSearchParams(filters).get('categoryId'))&&<div className="ledger-chart-filter"><span>{selectedDay??'已筛选分类'}</span><button type="button" onClick={()=>{onDay(null);onCategory(null);}}><X size={14}/>清除图表筛选</button></div>}
 </section>;
}
