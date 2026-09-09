import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight,WalletCards} from 'lucide-react';
import {money,type RequestFn} from '../common';

export interface CashPosition {
 asOf:string;currency:'CNY';availableCash:string|null;knownAvailableCash:string;uninitializedCount:number;
 unconverted:Array<{accountId:number;currency:string;nativeAmount:string}>;
}
export function HomeCashPosition({request}:{request:RequestFn}){
 const query=useQuery({queryKey:['cash-position'],queryFn:()=>request<CashPosition>('/api/cash-position'),retry:false});
 const data=query.data;
 return <section className="home-wealth home-surface home-available-cash" aria-label="账内可用现金">
  <header className="home-section-heading"><h2><WalletCards size={19} aria-hidden="true"/>可用现金</h2><a className="home-icon-button" href="/workspace/transactions?section=accounts" aria-label="查看现金账户"><ArrowUpRight size={21}/></a></header>
  <strong className="home-wealth-value">{query.isLoading?'读取中':query.error?'暂不可用':data?.availableCash==null?'待补齐':money(data.availableCash)}</strong>
  {query.error?<button className="home-link" onClick={()=>void query.refetch()}>重试现金余额</button>:<>
   {!!data?.uninitializedCount&&<a className="home-data-status" href="/workspace/transactions?section=accounts">核对账户期初</a>}
   {!!data?.unconverted?.length&&<a className="home-data-status" href="/workspace/investments?tab=rates">补充汇率</a>}
  </>}
  <details className="home-cash-definition"><summary>计算口径</summary><p>账内现金余额合计，不含股票、房产或授信额度，不扣减贷款。实际还款仍以所选账户的余额为准。</p>{data?.availableCash==null&&!query.isLoading&&!query.error&&<p>已确认部分：{money(data?.knownAvailableCash)}</p>}</details>
 </section>;
}
