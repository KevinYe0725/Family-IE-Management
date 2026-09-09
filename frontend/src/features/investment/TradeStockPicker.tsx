import {useEffect,useId,useRef,useState} from 'react';
import {useMutation,useQuery} from '@tanstack/react-query';
import Select from '@douyinfe/semi-ui/lib/es/select';
import type {Page,Security} from '../../api/contracts';
import {StockLabel} from './StockLabel';
import {useStockDropdown} from './useStockDropdown';
import {useStockSearch} from './useStockSearch';
import {isOverseasInstrument,type OverseasInstrument,type OverseasSearch} from './overseas-market';
import {FormError,type RequestFn} from '../common';
import './stock-picker.scss';
export type SecuritySelection=Pick<Security,'id'|'tsCode'|'name'> & Partial<Security>;
export function securityCurrency(value?:SecuritySelection|null){return value?.currency??(value?.market==='HK'?'HKD':value?.market==='US'?'USD':'CNY');}
type Market='ALL'|'CN'|'HK'|'US';
type Choice={key:string;name:string;code:string;exchange:string;accessibleLabel?:string;security?:SecuritySelection;instrument?:OverseasInstrument};
const selectionMarket=(value:SecuritySelection):Market=>value.market==='HK'||value.market==='US'?value.market:'CN';
const domesticChoice=(value:SecuritySelection):Choice=>({key:'CN:'+value.id,name:value.name,code:value.tsCode.split('.')[0],exchange:value.market??value.tsCode.split('.')[1],accessibleLabel:value.tsCode+' · '+value.name,security:value});
const foreignChoice=(value:OverseasInstrument):Choice=>({key:value.market+':'+value.symbol,name:value.name,code:value.symbol,exchange:value.exchange,instrument:value});
export function TradeStockPicker({request,value,onChange,disabled=false}:{request:RequestFn;value:SecuritySelection|null;onChange:(value:Security|null)=>void;disabled?:boolean}){
 const [market,setMarket]=useState<Market>(()=>value?selectionMarket(value):'ALL');
 useEffect(()=>{if(value)setMarket(selectionMarket(value));},[value?.id,value?.market]);
 return <div><nav className="market-switch" aria-label="投资市场">{([['ALL','全部'],['CN','A 股'],['HK','港股'],['US','美股']] as const).map(([key,label])=><button type="button" key={key} disabled={disabled} aria-pressed={market===key} onClick={()=>{if(key!==market){setMarket(key);onChange(null);}}}>{label}</button>)}</nav>
  <MarketPicker market={market} request={request} value={value} onChange={onChange} disabled={disabled}/>
 </div>;
}
function MarketPicker({market,request,value,onChange,disabled}:{market:Market;request:RequestFn;value:SecuritySelection|null;onChange:(value:Security)=>void;disabled:boolean}){
 const id=useId(),pickerId=id+'-trade-search';const dropdown=useStockDropdown(pickerId);
 const {query,setQuery,debounced,composing,compositionProps}=useStockSearch();
 const alive=useRef(true),generation=useRef(0);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{generation.current++;},[market,disabled]);
 const catalog=useQuery({queryKey:['security-catalog'],queryFn:()=>request<{state:string;count:number}>('/api/securities/catalog-status'),enabled:!disabled&&(market==='ALL'||market==='CN'),retry:false,staleTime:60000});
 const catalogAvailable=(catalog.data?.count??0)>0&&['READY','ERROR'].includes(catalog.data?.state??'');
 const cn=useQuery({queryKey:['securities','search-page',debounced],enabled:!disabled&&catalogAvailable&&(market==='ALL'||market==='CN'),retry:false,staleTime:60000,
  queryFn:async({signal})=>{
   const result=await request<Page<Security>>('/api/securities/search?q='+encodeURIComponent(debounced)+'&page=0&size=20',{responseType:'page',signal});
   if(!result||!Array.isArray(result.items))throw new Error('A股目录响应不完整');return result;
  }});
 const loadForeign=async(m:'HK'|'US',signal:AbortSignal)=>{
  const result=await request<OverseasSearch>('/api/overseas-market/search?market='+m+'&q='+encodeURIComponent(debounced),{signal});
  if(!result||!Array.isArray(result.items)||result.items.length>20||!['READY','SYNCING','ERROR'].includes(result.state)||typeof result.stale!=='boolean'||typeof result.hasNext!=='boolean'||result.items.some(item=>!isOverseasInstrument(item,m)))throw new Error('股票目录响应不匹配');return result;
 };
 const hk=useQuery({queryKey:['overseas-search','HK',debounced],queryFn:({signal})=>loadForeign('HK',signal),enabled:!disabled&&(market==='ALL'||market==='HK'),retry:false,staleTime:60000});
 const us=useQuery({queryKey:['overseas-search','US',debounced],queryFn:({signal})=>loadForeign('US',signal),enabled:!disabled&&(market==='ALL'||market==='US'),retry:false,staleTime:60000});
 const active=[...(market==='ALL'||market==='CN'?[{label:'A股',state:cn}]:[]),...(market==='ALL'||market==='HK'?[{label:'港股',state:hk}]:[]),...(market==='ALL'||market==='US'?[{label:'美股',state:us}]:[])];
 const waiting=composing||query.trim()!==debounced||catalog.isFetching||active.some(item=>item.state.isFetching);
 const options:Choice[]=[
  ...(market==='ALL'||market==='CN'?(cn.data?.items??[]).map(domesticChoice):[]),
  ...(market==='ALL'||market==='HK'?(hk.data?.items??[]).map(foreignChoice):[]),
  ...(market==='ALL'||market==='US'?(us.data?.items??[]).map(foreignChoice):[])
 ];
 const selected=value?(selectionMarket(value)==='CN'?domesticChoice(value):{key:value.market+':'+value.symbol,name:value.name,code:value.symbol??value.tsCode,exchange:value.exchange??value.market??'',security:value}):null;
 if(selected&&!options.some(item=>item.key===selected.key))options.unshift(selected);
 const resolve=useMutation({mutationFn:async(item:OverseasInstrument)=>{
  const version=generation.current;
  const security=await request<Security>('/api/securities/overseas/resolve',{method:'POST',body:{market:item.market,symbol:item.symbol}});
  if(!security?.id||security.market!==item.market||security.symbol!==item.symbol||security.currency!==item.currency)throw new Error('股票登记响应不匹配，请重试');
  return {security,version};
 },onSuccess:result=>{if(alive.current&&result.version===generation.current)onChange(result.security);}});
 const label=(item:Choice)=><StockLabel name={item.name} code={item.code} exchange={item.exchange} accessibleLabel={item.accessibleLabel??item.code+' · '+item.name}/>;
 const unavailable=(item:Choice)=>{
  if(!item.instrument)return item.key.startsWith('CN:')&&!catalogAvailable;
  const directory=item.instrument.market==='HK'?hk.data:us.data;
  return item.instrument.currency!==(item.instrument.market==='HK'?'HKD':'USD')||directory?.state!=='READY'||Boolean(directory.stale);
 };
 const directoryIssue=(data:unknown)=>Boolean(data&&typeof data==='object'&&(('state' in data&&data.state==='ERROR')||('stale' in data&&data.stale===true)));
 return <div className="stock-picker" id={pickerId} style={{position:'relative'}} {...compositionProps}>
  <span id={id+'-label'} className="stock-picker__label">证券</span>
  <Select ref={dropdown.selectRef} data-field="securityId" aria-labelledby={id+'-label'} aria-required filter remote onChangeWithObject className="stock-picker__select"
   inputProps={{maxLength:80}} value={selected?{value:selected.key,label:label(selected),choice:selected}:undefined} disabled={disabled||resolve.isPending}
   placeholder="搜索股票代码或名称，直接选择" onSearch={setQuery} onDropdownVisibleChange={dropdown.setMenuOpen}
   style={{width:'100%'}} dropdownMatchSelectWidth dropdownClassName="stock-picker-dropdown" dropdownStyle={{width:dropdown.controlWidth||'100%',minWidth:0}} rePosKey={dropdown.controlWidth}
   getPopupContainer={()=>document.getElementById(pickerId)!}
   optionList={(composing||query.trim()!==debounced?[]:options).map(item=>({value:item.key,label:label(item),choice:item,disabled:unavailable(item)}))}
   onSelect={(_next,option)=>{if(composing)return;const item=option.choice as Choice;if(unavailable(item))return;if(item.security)onChange(item.security as Security);else if(item.instrument)resolve.mutate(item.instrument);}}
   emptyContent={waiting?'正在查找股票…':'没有找到匹配股票，试试代码或切换市场'}/>
  {waiting&&<p className="stock-picker-status" role="status">正在查找股票，可继续输入…</p>}
  {!disabled&&(market==='ALL'||market==='CN')&&!catalog.isFetching&&(!catalogAvailable||catalog.data?.state==='ERROR'||catalog.error)&&<p role="status">{catalogAvailable?'A股目录更新失败，使用已有目录。':'A股目录尚未就绪或未启用。'}<button type="button" onClick={()=>void catalog.refetch()}>重试A股目录</button></p>}
  {active.filter(item=>item.state.error||directoryIssue(item.state.data)).map(item=><p role="status" key={item.label}>{item.label}搜索或目录更新暂不可用，请刷新后选择。<button type="button" onClick={()=>void item.state.refetch()}>重试{item.label}</button></p>)}
  {active.some(item=>'state' in (item.state.data??{})&&(item.state.data as OverseasSearch).state==='SYNCING')&&<p role="status">部分市场目录正在准备<button type="button" onClick={()=>active.forEach(item=>void item.state.refetch())}>重试目录</button></p>}
  <FormError error={resolve.error}/>
 </div>;
}
