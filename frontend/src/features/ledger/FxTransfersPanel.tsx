import {BankAccountPicker} from './BankAccountPicker';
import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import type {Account,HouseholdRole,Page} from '../../api/contracts';
import {ApiError} from '../../api/client';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import {DateField} from '../../shared/DateField';
import {PaginationControls,usePageRecovery} from '../../shared/pagination';
import {AccountOptions,PaymentPreview,cents,sumMoney,useFundsRefresh} from '../accounting';
import {DataPanel,ActionDialog,ConfirmDialog,FormError,QueryState,money,isManager,type RequestFn} from '../common';
import {AccountingHistory} from './accounting-flows';
import {dailyFxPair,estimateFxArrival,type DailyFxTable} from './daily-fx';
import './fx-transfers.scss';
interface FxRow{id:number;fromAccountId:number;toAccountId:number;fromCurrency:string;toCurrency:string;fromAmount:string;toAmount:string;fee:string;actualRate:string;occurredOn:string;revision:number;reversed:boolean}
interface Draft{id?:number;fromAccountId:string;toAccountId:string;fromAmount:string;toAmount:string;fee:string;occurredOn:string;idempotencyKey:string;expectedRevision?:number}
type Attempt={id?:number;body:Body;from?:Account;to?:Account};
const pendingKey=['pending-fx-exchange'] as const;
type Body=Omit<Draft,'id'|'fromAccountId'|'toAccountId'> & {fromAccountId:number;toAccountId:number};
const definite=new Set(['VALIDATION_ERROR','INSUFFICIENT_FUNDS','ACCOUNT_ARCHIVED','ACCOUNTING_NOT_INITIALIZED','ACCOUNT_ACTIVITY_BEFORE_OPENING','FX_REVISION_CHANGED','FX_TRANSFER_REVERSED']);
function ratio(from:string,to:string){
 const parse=(v:string)=>{if(!/^\d+(\.\d{1,12})?$/.test(v))return null;const [a,b='']=v.split('.');return BigInt(a)*1000000000000n+BigInt(b.padEnd(12,'0'));};
 const a=parse(from),b=parse(to);if(a==null||b==null||a<=0n)return '—';const value=(b*1000000n+a/2n)/a;return `${value/1000000n}.${String(value%1000000n).padStart(6,'0')}`;
}
export function FxTransfersPanel({request,role,accounts,accountsReady=true,initialBankId,onInitialHandled,onOverlayChange}:{request:RequestFn;role:HouseholdRole;accounts:Account[];accountsReady?:boolean;initialBankId?:number|null;onInitialHandled?:()=>void;onOverlayChange?:(open:boolean)=>void}){
 const cache=useQueryClient(),fundsError=useFundsRefresh();
 const [page,setPage]=useState(0),[draft,setDraft]=useState<Draft|null>(null),[audit,setAudit]=useState<number|null>(null);
 const [estimated,setEstimated]=useState(false);
 // Unknown financial outcomes must survive panel switches until resolved or the auth cache is cleared.
 const pending=useQuery<Attempt|null>({queryKey:pendingKey,queryFn:()=>null,enabled:false,initialData:null,gcTime:Infinity});
 const attempt=pending.data??null;
 const setAttempt=(value:Attempt|null)=>{cache.setQueryData(pendingKey,value);};
 const [reversing,setReversing]=useState<{row:FxRow;key:string}|null>(null);
 useEffect(()=>{onOverlayChange?.(draft!==null||audit!==null||reversing!==null);},[draft,audit,reversing,onOverlayChange]);
 useEffect(()=>()=>onOverlayChange?.(false),[onOverlayChange]);
 const rows=useQuery({queryKey:['fx-transfers',page],queryFn:()=>request<Page<FxRow>>(`/api/fx-transfers?page=${page}&size=20`)});usePageRecovery(page,rows.data,setPage);
 const refresh=async()=>{await Promise.all(['fx-transfers','accounts','accounting-history','net-worth','dashboard','portfolio','budget-usage'].map(key=>cache.invalidateQueries({queryKey:[key]})));};
 const save=useMutation({mutationFn:(value:NonNullable<typeof attempt>)=>request<FxRow>(value.id?`/api/fx-transfers/${value.id}`:'/api/fx-transfers',{method:value.id?'PATCH':'POST',body:value.body}),
  onError:error=>{fundsError(error);if(error instanceof ApiError&&definite.has(error.code??''))setAttempt(null);},onSuccess:async()=>{setAttempt(null);setDraft(null);await refresh();}});
 const undo=useMutation({mutationFn:(value:NonNullable<typeof reversing>)=>request(`/api/fx-transfers/${value.row.id}?expectedRevision=${value.row.revision}`,{method:'DELETE',headers:{'Idempotency-Key':value.key}}),onError:fundsError,onSuccess:async()=>{setReversing(null);await refresh();}});
 const from=attempt?.from??accounts.find(a=>String(a.id)===draft?.fromAccountId),to=attempt?.to??accounts.find(a=>String(a.id)===draft?.toAccountId);
 const total=draft?sumMoney(draft.fromAmount,draft.fee||'0'):null;
 const ready=!!draft&&!!from&&!!to&&from.id!==to.id&&from.currency!==to.currency&&from.openingConfirmed&&to.openingConfirmed
  &&(cents(draft.fromAmount)??0n)>0n&&(cents(draft.toAmount)??0n)>0n&&(cents(draft.fee||'0')??-1n)>=0n;
 const reference=useQuery({queryKey:['exchange-rates',draft?.occurredOn],queryFn:()=>request<DailyFxTable>(`/api/exchange-rates?asOf=${draft!.occurredOn}`),enabled:!!draft?.occurredOn&&!attempt,retry:false,staleTime:60000,refetchInterval:draft?.occurredOn===businessDate()&&!attempt?60000:false});
 const refreshReference=useMutation({mutationFn:(day:string)=>request<DailyFxTable>(`/api/exchange-rates/refresh?asOf=${day}`,{method:'POST'}),onSuccess:(data,day)=>cache.setQueryData(['exchange-rates',day],data)});
 const refreshError=refreshReference.variables===draft?.occurredOn?refreshReference.error:null;
 const pair=dailyFxPair(reference.data,from?.currency,to?.currency,draft?.occurredOn);
 const estimatedArrival=pair&&draft?estimateFxArrival(draft.fromAmount,pair.fromRate,pair.toRate):null;
 const rateBusy=reference.isFetching||refreshReference.isPending;
 const update=(field:keyof Draft,value:string)=>{
  const contextChanged=['fromAccountId','toAccountId','fromAmount','occurredOn'].includes(field);
  if(contextChanged&&refreshReference.error)refreshReference.reset();
  save.reset();setDraft(old=>old?{...old,[field]:value,...(estimated&&contextChanged?{toAmount:''}:{}),idempotencyKey:newIdempotencyKey()}:old);
  if(contextChanged||field==='toAmount')setEstimated(false);
 };
 useEffect(()=>{if(!draft){setEstimated(false);refreshReference.reset();}},[!!draft]);
 const blank=()=>({fromAccountId:'',toAccountId:'',fromAmount:'',toAmount:'',fee:'0',occurredOn:businessDate(),idempotencyKey:newIdempotencyKey()});
 useEffect(()=>{
  if(!initialBankId||!isManager(role))return;
  const children=accounts.filter(a=>a.bankAccountId===initialBankId&&!a.archivedAt&&a.openingConfirmed);
  if(children.length<2)return;
  // A lost response remains the priority; never replace a frozen financial request.
  if(attempt)setDraft({...attempt.body,id:attempt.id,fromAccountId:String(attempt.body.fromAccountId),toAccountId:String(attempt.body.toAccountId)});
  else setDraft({...blank(),fromAccountId:String(children[0].id),toAccountId:String(children[1].id)});
  onInitialHandled?.();
 },[initialBankId,accounts,role]);
 return <><DataPanel title="换汇记录" meta="记录实际到账，不执行银行或券商兑换。" action={isManager(role)&&<button type="button" className="secondary-action" onClick={()=>{save.reset();setDraft(attempt?{...attempt.body,id:attempt.id,fromAccountId:String(attempt.body.fromAccountId),toAccountId:String(attempt.body.toAccountId)}:blank());}}>{attempt?'继续核对上次换汇':'记录换汇'}</button>}>
  <QueryState loading={rows.isLoading} error={rows.error} empty={!rows.data?.items.length} emptyTitle="还没有换汇记录" emptyDetail="不同币种之间的资金移动，在这里同时记录转出本金、手续费和到账金额。">
   <div className="responsive-data"><table><thead><tr><th>日期</th><th>转出本金</th><th>到账金额</th><th>手续费</th><th>状态 / 操作</th></tr></thead><tbody>{rows.data?.items.map(row=><tr key={row.id}>
    <td>{row.occurredOn}</td><td>{money(row.fromAmount,row.fromCurrency)}</td><td>{money(row.toAmount,row.toCurrency)}</td><td>{money(row.fee,row.fromCurrency)}</td><td>
     {row.reversed?'已冲销':`第 ${row.revision} 版`} <button type="button" className="text-action" onClick={()=>setAudit(row.id)}>账务历史</button>
     {isManager(role)&&!row.reversed&&<><button type="button" className="text-action" disabled={!!attempt} onClick={()=>{save.reset();setAttempt(null);setDraft({id:row.id,fromAccountId:String(row.fromAccountId),toAccountId:String(row.toAccountId),fromAmount:row.fromAmount,toAmount:row.toAmount,fee:row.fee,occurredOn:row.occurredOn,expectedRevision:row.revision,idempotencyKey:newIdempotencyKey()});}}>更正</button><button type="button" className="text-action danger" disabled={!!attempt} onClick={()=>{undo.reset();setReversing({row,key:newIdempotencyKey()});}}>冲销</button></>}
    </td></tr>)}</tbody></table></div><PaginationControls page={page} totalPages={rows.data?.totalPages??0} hasNext={rows.data?.hasNext??false} onPageChange={setPage} label="换汇记录"/>
  </QueryState>
 </DataPanel>
 <ActionDialog open={draft!==null} title={draft?.id?'更正换汇':'记录换汇'} draft={{draft,attempt:attempt?.body}} busy={save.isPending} onClose={()=>setDraft(null)}>{draft&&<form className="feature-form" onSubmit={e=>{e.preventDefault();if(attempt){save.mutate(attempt);return;}if(!ready)return;const {id,...fields}=draft;const value={id,body:{...fields,fromAccountId:Number(draft.fromAccountId),toAccountId:Number(draft.toAccountId)},from:from&&{...from},to:to&&{...to}};setAttempt(value);save.mutate(value);}}>
  <FormError error={save.error}/><fieldset className="feature-form" disabled={!!attempt||save.isPending}>
   <BankAccountPicker accountsReady={accountsReady} label="转出账户" name="fromAccountId" accounts={accounts} request={request} disabled={!!attempt} value={draft.fromAccountId} onChange={id=>update("fromAccountId",id)}/>
   <BankAccountPicker accountsReady={accountsReady} label="转入账户" name="toAccountId" accounts={accounts} request={request} disabled={!!attempt} value={draft.toAccountId} onChange={id=>update("toAccountId",id)}/>
   <label>实际转出本金<input required inputMode="decimal" value={draft.fromAmount} onChange={e=>update('fromAmount',e.target.value)}/></label>
   {from&&to&&from.currency!==to.currency&&<section className="daily-fx-reference" aria-label="日度参考汇率">
    <div>{pair?<><strong>1 {from.currency} = {ratio(pair.toRate,pair.fromRate)} {to.currency}</strong><span>日度参考 · {pair.effectiveOn}</span></>:<span>{rateBusy?'正在读取日度参考汇率…':'暂无可用的日度参考汇率，可手动填写实际到账。'}</span>}</div>
    <div className="daily-fx-actions"><button type="button" className="text-action" disabled={rateBusy} onClick={()=>refreshReference.mutate(draft.occurredOn)}>刷新参考汇率</button><button type="button" className="secondary-action" disabled={!estimatedArrival||rateBusy||!!reference.error||!!refreshError} onClick={()=>{if(estimatedArrival){update('toAmount',estimatedArrival);setEstimated(true);}}}>使用日度参考汇率</button></div>
    <FormError compact error={refreshError??reference.error}/>
   </section>}
   <label>实际到账金额<input required inputMode="decimal" value={draft.toAmount} onChange={e=>update('toAmount',e.target.value)}/></label>
   {estimated&&<p className="source-note" role="status">已填入参考估算，请核对银行实际到账金额。</p>}
   <label>手续费（{from?.currency??'CNY'}）<input inputMode="decimal" value={draft.fee} onChange={e=>update('fee',e.target.value)}/></label>
   <label>实际换汇日期<DateField required max={businessDate()} value={draft.occurredOn} onChange={e=>update('occurredOn',e.target.value)}/></label>
  </fieldset>
  {from&&to&&<p>实际汇率：1 {from.currency} = {ratio(draft.fromAmount,draft.toAmount)} {to.currency}（不含手续费）</p>}
  <PaymentPreview account={from} amount={total} adjustment={!!draft.id}/><PaymentPreview account={to} amount={draft.toAmount} incoming adjustment={!!draft.id}/>
  {attempt&&!save.isPending&&<p role="status">上次结果尚未确认，请用原请求核对，避免重复记录。</p>}
  <button type="submit" disabled={save.isPending||(!attempt&&!ready)}>{attempt?'核对本次换汇结果':draft.id?'确认更正换汇':'确认记录换汇'}</button>
 </form>}</ActionDialog>
 <ConfirmDialog open={!!reversing} title="冲销这笔换汇？" confirmLabel={undo.error?'核对冲销结果':'确认冲销'} loading={undo.isPending} danger detail={<><p>同时冲回两边本金和手续费。若到账资金已使用，会拒绝整笔冲销。</p><FormError error={undo.error}/></>} onClose={()=>setReversing(null)} onConfirm={()=>reversing&&undo.mutate(reversing)}/>
 <ActionDialog open={audit!==null} title="换汇账务历史" onClose={()=>setAudit(null)}>{audit!==null&&<AccountingHistory request={request} source={{sourceType:'FX_TRANSFER',sourceId:audit}}/>}</ActionDialog>
 </>;
}
