import {useContext,useEffect,useRef,useState} from 'react';
import {AuthContext} from '../../auth/AuthProvider';
import type {Account,BankAccount} from '../../api/contracts';
import {isManager,money,type RequestFn} from '../common';
import {BankAccountEditor} from './BankAccountEditor';
import './bank-accounts.scss';

type Props={accounts:Account[];accountsReady?:boolean;value?:string;onChange:(id:string)=>void;currency?:string;label:string;name?:string;disabled?:boolean;required?:boolean;request?:RequestFn;excludeId?:number;currencies?:string[];canManage?:boolean};
export function BankAccountPicker({accounts,accountsReady=true,value='',onChange,currency,label,name,disabled=false,required=true,request,excludeId,currencies=['CNY','HKD','USD'],canManage}:Props){
 const auth=useContext(AuthContext);const managementAllowed=canManage??Boolean(auth?.session&&isManager(auth.session.role));
 const [created,setCreated]=useState<Account|null>(null);
 const all=created&&!accounts.some(a=>a.id===created.id)?[...accounts,created]:accounts;
 const selected=all.find(a=>String(a.id)===value);
 const [localCurrency,setCurrency]=useState(selected?.currency??currencies[0]??'CNY');
 const target=currency??localCurrency;
 const [bankId,setBankId]=useState<number|null>(selected?.bankAccountId??null);
 const [adding,setAdding]=useState(false);
 const prior=useRef(value),previousCurrency=useRef(target),wasReady=useRef(accountsReady),selectRef=useRef<HTMLSelectElement>(null);
 useEffect(()=>{
  if(!accountsReady){wasReady.current=false;return;}
  if(!wasReady.current){wasReady.current=true;if(selected&&!currency){setCurrency(selected.currency??'CNY');setBankId(selected.bankAccountId??null);return;}}
  if(created&&accounts.some(a=>a.id===created.id))setCreated(null);
  if(value&&(!selected||selected.archivedAt)){onChange('');return;}
  const currencyChanged=previousCurrency.current!==target;previousCurrency.current=target;
  if(value!==prior.current){prior.current=value;if(selected)setBankId(selected.bankAccountId??null);}
  if(!selected&&currencyChanged&&bankId){const match=all.find(a=>a.bankAccountId===bankId&&(a.currency??'CNY')===target&&!a.archivedAt&&a.id!==excludeId);if(match)onChange(String(match.id));return;}
  if(!selected||(selected.currency??'CNY')===target)return;
  const match=selected.bankAccountId?all.find(a=>a.bankAccountId===selected.bankAccountId&&(a.currency??'CNY')===target&&!a.archivedAt&&a.id!==excludeId):undefined;
  setBankId(selected.bankAccountId??null);onChange(match?String(match.id):'');
 },[value,target,selected?.id,accounts,accountsReady,excludeId]);
 const groups=new Map<string,{bank?:number;name:string;sample:Account;match?:Account}>();
 for(const a of all){if(a.archivedAt)continue;const key=a.bankAccountId?`bank:${a.bankAccountId}`:`account:${a.id}`;
  if(!a.bankAccountId&&(a.currency??'CNY')!==target)continue;
  const entry=groups.get(key)??{bank:a.bankAccountId??undefined,name:a.bankAccountName??a.name,sample:a};
  if((a.currency??'CNY')===target)entry.match=a;groups.set(key,entry);
 }
 const missing=bankId?groups.get(`bank:${bankId}`):undefined;
 const shown=value&&(selected?.currency??'CNY')===target?value:missing&&!missing.match?`bank:${bankId}`:'';
 const bank:BankAccount|undefined=missing?.bank?{id:missing.bank,name:missing.name,bankName:missing.sample.bankName??null,cardLastFour:missing.sample.cardLastFour??null,archivedAt:null,accounts:all.filter(a=>a.bankAccountId===missing.bank)}:undefined;
 useEffect(()=>{selectRef.current?.setCustomValidity(!accountsReady&&(required||!!value)?'账户尚未读取完成':(required&&!value)||(!!value&&(!selected||selected.archivedAt||!selected.openingConfirmed||selected.openingOn===null||selected.id===excludeId||(selected.currency??'CNY')!==target))?'请选择已初始化的对应币种账户':'');},[accountsReady,required,value,target,selected?.id,selected?.archivedAt,selected?.currency,selected?.openingConfirmed,selected?.openingOn,excludeId]);
 return <div className="bank-account-picker">
  {!currency&&<label>{label}币种<select aria-label={`${label}币种`} value={target} disabled={disabled||!accountsReady} onChange={e=>setCurrency(e.target.value)}>{currencies.map(c=><option key={c}>{c}</option>)}</select></label>}
  <label>{label}<select ref={selectRef} aria-label={label} name={name} required={required} disabled={disabled} value={accountsReady?shown:''} onChange={e=>{
   const id=e.target.value;if(id.startsWith('bank:')){setBankId(Number(id.slice(5)));onChange('');return;}
   setBankId(all.find(a=>String(a.id)===id)?.bankAccountId??null);onChange(id);
  }}><option value="">{accountsReady?'请选择账户':'正在读取账户…'}</option>{accountsReady&&[...groups.entries()].map(([key,g])=><option key={key} value={g.match?String(g.match.id):key} disabled={!!g.match&&(g.match.id===excludeId||!g.match.openingConfirmed||g.match.openingOn===null)}>{g.name}{g.sample.cardLastFour?` · ${g.sample.cardLastFour}`:''}{g.match?` · ${target} ${g.match.openingConfirmed?money(g.match.availableBalance,target):'待初始化'}`:` · 尚未添加 ${target}`}</option>)}</select></label>
  {missing&&!missing.match&&<div className="bank-picker-missing" role="status"><span>此卡尚未添加 {target} 余额</span>{managementAllowed&&request&&bank?<button type="button" className="text-action" disabled={disabled} onClick={()=>setAdding(true)}>添加 {target} 余额</button>:<span>请管理员添加此币种余额。</span>}</div>}
  {managementAllowed&&adding&&request&&bank&&<BankAccountEditor request={request} bank={bank} mode="balance" currency={target} onClose={()=>setAdding(false)} onSaved={result=>{const child=result.accounts.find(a=>a.currency===target&&!a.archivedAt);setAdding(false);if(child){setCreated(child);onChange(String(child.id));}}}/>}
 </div>;
}
