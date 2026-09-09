import {useEffect,useState} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import type {Portfolio} from '../../api/contracts';
import type {RequestFn} from '../common';

export interface LiveQuote {securityId:number;price:string|null;currency:string;source:string;quotedAt:string|null;fetchedAt:string|null;status:string;marketState:string;ageSeconds:number|null;delayMinutes:number|null}
interface LivePortfolio {portfolio:Portfolio;quotes:LiveQuote[];nextRefreshSeconds:number;partial:boolean}
interface LivePrices {quotes:LiveQuote[];nextRefreshSeconds:number}
/** Reference prices may change; recorded quantities/costs must match the latest ledger response. */
export function sameRecordedPositions(current:Portfolio|undefined,live:Portfolio|undefined){
 if(!current||!live||!Array.isArray(current.positions)||!Array.isArray(live.positions)||current.positions.length!==live.positions.length)return false;
 return current.positions.every(position=>{
  const other=live.positions.find(p=>p.accountId===position.accountId&&p.securityId===position.securityId);
  return other&&String(other.quantity)===String(position.quantity)&&other.cost===position.cost&&other.realizedProfit===position.realizedProfit
   &&other.base?.cost===position.base?.cost&&other.base?.realizedProfit===position.base?.realizedProfit
   &&!(position.source==='MANUAL'&&(other.source!=='MANUAL'||position.price!==other.price))
   &&!(position.tradeDate&&other.tradeDate&&position.tradeDate>other.tradeDate);
 });
}
function useVisible(){
 const [visible,setVisible]=useState(()=>document.visibilityState!=='hidden');
 useEffect(()=>{const changed=()=>setVisible(document.visibilityState!=='hidden');document.addEventListener('visibilitychange',changed);return()=>document.removeEventListener('visibilitychange',changed);},[]);
 return visible;
}
function interval(seconds:number|undefined){return Math.max(60,Math.min(259200,Number.isFinite(seconds)?seconds!:60))*1000;}
export function useLivePortfolio(request:RequestFn,enabled:boolean){
 const visible=useVisible(),cache=useQueryClient();
 useEffect(()=>{if(!visible||!enabled)void cache.cancelQueries({queryKey:['portfolio','live'],exact:true});},[visible,enabled,cache]);
 return useQuery<LivePortfolio>({queryKey:['portfolio','live'],enabled:enabled&&visible,retry:false,staleTime:60000,refetchOnWindowFocus:false,refetchIntervalInBackground:false,
  refetchInterval:query=>enabled&&visible?interval(query.state.data?.nextRefreshSeconds):false,
  queryFn:async({signal})=>{
   const value=await request<LivePortfolio>('/api/portfolio/live',{signal});
   if(!value?.portfolio||!Array.isArray(value.portfolio.positions)||!Array.isArray(value.quotes))throw new Error('盘中参考价暂不可用，保留收盘估值');
   return value;
  }});
}
export function useLiveQuote(request:RequestFn,securityId:number,currency:string){
 const visible=useVisible();
 return useQuery<{quote:LiveQuote|undefined;nextRefreshSeconds:number}>({queryKey:['spot-quote',securityId],enabled:visible&&securityId>0,retry:false,staleTime:60000,refetchOnWindowFocus:false,refetchIntervalInBackground:false,
  refetchInterval:query=>visible?interval(query.state.data?.nextRefreshSeconds):false,
  queryFn:async({signal})=>{
   const response=await request<LivePrices>('/api/market-quotes/live?securityIds='+securityId,{signal});
   if(!response||!Array.isArray(response.quotes))throw new Error('盘中参考报价暂不可用');
   const quote=response.quotes.find(q=>q.securityId===securityId);
   if(quote&&quote.currency!==currency)throw new Error('报价币种不匹配');
   return {quote,nextRefreshSeconds:response.nextRefreshSeconds};
  }});
}
export function quoteTime(value:string|null|undefined){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'时间待核对';}
