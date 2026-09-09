import {useContext,useEffect,useState} from 'react';
import {Pin,RefreshCw,X} from 'lucide-react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import {AuthContext} from '../../auth/AuthProvider';
import {ActionDialog,type RequestFn} from '../common';
import {TradeStockPicker,type SecuritySelection} from './TradeStockPicker';
import {StockChart} from './StockChart';
import {useLiveQuote} from './live-quotes';
import './pinned-stock.scss';

function read(key:string|null):SecuritySelection|null{
 try{const s=key?JSON.parse(localStorage.getItem(key)??'null'):null;
  if(!s||!Number.isSafeInteger(s.id)||s.id<=0||typeof s.name!=='string'||typeof s.tsCode!=='string'||(s.market&&!['CN','SH','SZ','BJ','HK','US'].includes(s.market))||((s.market==='HK'||s.market==='US')&&typeof s.symbol!=='string'))return null;
  const market=s.market??'CN',currency=market==='HK'?'HKD':market==='US'?'USD':'CNY';
  if(s.currency&&s.currency!==currency)return null;
  return {id:s.id,name:s.name,tsCode:s.tsCode,market,currency,symbol:typeof s.symbol==='string'?s.symbol:undefined,exchange:typeof s.exchange==='string'?s.exchange:undefined,timezone:market==='US'?'America/New_York':market==='HK'?'Asia/Hong_Kong':'Asia/Shanghai'};
 }catch{return null;}
}
export function PinnedStockCard({request,page}:{request:RequestFn;page:'home'|'investments'}){
 const session=useContext(AuthContext)?.session;
 const scope=session?`family-finance:pinned-stock:v1:${session.householdId}:${session.userId}:${page}`:null;
 return <PinnedContent key={scope??page} request={request} storageKey={scope}/>;
}
function PinnedContent({request,storageKey}:{request:RequestFn;storageKey:string|null}){
 const [stock,setStock]=useState(()=>read(storageKey)),[choosing,setChoosing]=useState(false),[draft,setDraft]=useState<SecuritySelection|null>(null),[storageError,setStorageError]=useState(false);
 useEffect(()=>{const sync=(e:StorageEvent)=>{if(e.key===storageKey||e.key===null)setStock(read(storageKey));};window.addEventListener('storage',sync);return()=>window.removeEventListener('storage',sync);},[storageKey]);
 function save(value:SecuritySelection|null){
  setStock(value);setStorageError(false);
  if(storageKey)try{if(value)localStorage.setItem(storageKey,JSON.stringify(value));else localStorage.removeItem(storageKey);}catch{setStorageError(true);}
  setChoosing(false);
 }
 return <section className="pinned-stock home-surface" aria-label="固定行情">
  <header className="pinned-stock-heading"><h2><Pin size={18}/>固定行情</h2><div>{stock&&<button className="text-action" onClick={()=>save(null)} aria-label="取消固定"><X size={16}/></button>}<Button onClick={()=>{setDraft(stock);setChoosing(true);}}>{stock?'更换股票':'固定一只股票'}</Button></div></header>
  {stock&&<PinnedQuote key={stock.id} request={request} stock={stock}/>}
  {storageError&&<p role="status">浏览器未允许保存，下次打开需重新选择。</p>}
  {choosing&&<ActionDialog open title="选择固定展示的股票" onClose={()=>setChoosing(false)}><TradeStockPicker watchOnly request={request} value={draft} onChange={setDraft}/><div className="pinned-stock-actions"><Button theme="solid" type="primary" disabled={!draft} onClick={()=>draft&&save(draft)}>固定展示</Button></div></ActionDialog>}
 </section>;
}
function PinnedQuote({request,stock}:{request:RequestFn;stock:SecuritySelection}){
 const currency=stock.currency??(stock.market==='HK'?'HKD':stock.market==='US'?'USD':'CNY');
 const live=useLiveQuote(request,stock.id,currency);
 return <><StockChart request={request} security={{...stock,currency}} compact liveQuote={live.data?.quote}/>{live.error&&<div className="pinned-stock-status" role="status"><span>盘中报价暂不可用</span><button className="text-action" onClick={()=>void live.refetch()}><RefreshCw size={14}/>重试</button></div>}</>;
}
