import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CandleResponse } from './chart-data';
import { dateText, money, type RequestFn } from '../common';
import type { MarketPrice } from '../../api/contracts';
import {useLiveQuote,quoteTime} from './live-quotes';

export function ReferenceQuote({request, security, onUsePrice,compact=false}: {
  request: RequestFn; security: {id:number;name:string;tsCode:string;market?:string;symbol?:string;currency?:string}; onUsePrice?: (price:string)=>void;compact?:boolean;
}) {
  const foreign=security.market==='HK'||security.market==='US';
  const spot=useLiveQuote(request,security.id,security.currency??(security.market==='HK'?'HKD':security.market==='US'?'USD':'CNY'));
  const symbol=foreign?security.symbol:security.tsCode;
  const query=useQuery({queryKey:foreign?['overseas-candles',security.market,symbol]:['security-candles',security.id,'none'],queryFn:async()=>{
    const response=await request<CandleResponse>(foreign?`/api/overseas-market/candles?market=${security.market}&symbol=${encodeURIComponent(symbol??'')}`:`/api/securities/${security.id}/candles?adjust=none`);
    const last=response?.bars?.at?.(-1);
    if(!response || !Array.isArray(response.bars) || response.symbol!==symbol || response.adjustment!=='none'
      || response.source!==(foreign?'SINA':'BAOSTOCK') || typeof response.supported!=='boolean'
      || (last && (!Number.isFinite(last.close) || last.close<=0 || !response.asOf)))throw new Error('报价响应不完整');
    return response;
  },staleTime:300_000,retry:false});
  const last=Array.isArray(query.data?.bars) ? query.data.bars.at(-1) : undefined;
  const live=spot.data?.quote;
  if(live?.price)return <aside className={`reference-quote${compact?' investment-reference-compact':''}`} aria-label="参考报价"><div><span>{live.status==='STALE'?'缓存盘中参考价':live.status==='DELAYED'?'延迟参考价':'盘中参考价'}</span><strong>{money(live.price,live.currency)}</strong>{onUsePrice&&<button type="button" className="text-action" onClick={()=>onUsePrice(live.price!)}>填入参考价</button>}</div><p>腾讯公开参考 · 报价时间（北京时间）{quoteTime(live.quotedAt)}</p><p>{compact?'参考报价，不等于成交价。':'可能存在延迟；参考价不等于实际成交价，不会自动填写成交单价。'}</p></aside>;
  if(query.isLoading)return <div className="reference-quote" role="status">正在获取参考报价…</div>;
  if(query.error)return <div className="reference-quote" role="status"><span>暂时无法获取参考报价</span><button type="button" className="text-action" onClick={()=>{void query.refetch();}}>重试报价</button></div>;
  if(!query.data?.supported)return <div className="reference-quote" role="status">当前行情源暂未覆盖这只股票</div>;
  if(!last)return <div className="reference-quote" role="status">暂未返回收盘报价，请核对实际成交记录。</div>;
  return <aside className={`reference-quote${compact?' investment-reference-compact':''}`} aria-label="参考报价">
    <div><span>参考收盘价</span><strong>{money(last.close,security.currency)}</strong>{onUsePrice && <button type="button" className="text-action" onClick={()=>onUsePrice(last.close.toFixed(foreign?6:2))}>填入参考价</button>}</div>
    <p>{foreign?'新浪日线':'BaoStock'} · {dateText(query.data.asOf)} · 不复权{query.data.stale ? ' · 缓存数据，更新暂不可用' : ''}</p>
    <p>{compact?'参考报价，不等于成交价。':'参考价不等于实际成交价；期初持仓请填写原有单位成本。'}</p>
  </aside>;
}

type QuotePosition={securityId:number;tsCode:string;quantity:number;price:string|null};
const REFRESH_WINDOW=['market-refresh-window'] as const;
export function useMissingQuotesRefresh(request:RequestFn, manager:boolean, positions?:QuotePosition[]) {
  const cache=useQueryClient();
  const attempted=useRef(new Set<string>());
  const [cooldownUntil,setCooldownUntil]=useState(()=>cache.getQueryData<number>(REFRESH_WINDOW)??0);
  const missing=[...new Set((positions??[]).filter(p=>p.quantity>0 && p.price===null && /\.(SH|SZ|HK|US)$/.test(p.tsCode)).map(p=>p.securityId))].sort((a,b)=>a-b).join(',');
  const refresh=useMutation({mutationFn:async()=>{
    const result=await request<{state:string;refreshed:number;error:string|null;quotes:MarketPrice[]}>('/api/market-quotes/refresh',{method:'POST'});
    if(result?.state!=='READY')throw new Error('暂时无法更新持仓报价，请稍后手动刷新。');
    await Promise.all(['portfolio','market-quotes'].map(key=>cache.invalidateQueries({queryKey:[key]})));
    return result;
  }});
  const mutate=refresh.mutate;
  const start=useCallback(()=>{
    if(!manager || refresh.isPending)return false;
    const previous=cache.getQueryData<number>(REFRESH_WINDOW)??0;
    if(previous>Date.now()){setCooldownUntil(previous);return false;}
    // The server counts every attempt, including failures, in its 60s window.
    const deadline=Date.now()+61_000;
    cache.setQueryData(REFRESH_WINDOW,deadline);
    setCooldownUntil(deadline);
    mutate();
    return true;
  },[cache,manager,mutate,refresh.isPending]);
  useEffect(()=>{
    if(!cooldownUntil)return;
    const timer=setTimeout(()=>{
      const shared=cache.getQueryData<number>(REFRESH_WINDOW)??0;
      setCooldownUntil(shared>Date.now()?shared:0);
    },Math.max(0,cooldownUntil-Date.now()));
    return ()=>clearTimeout(timer);
  },[cache,cooldownUntil]);
  useEffect(()=>{
    if(!manager || !missing || cooldownUntil>Date.now() || refresh.isPending || attempted.current.has(missing))return;
    if(start())attempted.current.add(missing);
  },[manager,missing,start,cooldownUntil,refresh.isPending]);
  return {mutate:start,data:refresh.data,error:refresh.error,isPending:refresh.isPending,coolingDown:cooldownUntil>Date.now()};
}
