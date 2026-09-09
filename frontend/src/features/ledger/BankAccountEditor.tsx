import {useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import type {BankAccount} from '../../api/contracts';
import {ActionDialog,FormError,type RequestFn} from '../common';
import {DateField} from '../../shared/DateField';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import Button from '@douyinfe/semi-ui/lib/es/button';
import './bank-accounts.scss';

export function BankAccountEditor({request,bank,mode='create',currency='CNY',currencies=['CNY','HKD','USD'],onClose,onSaved}:{request:RequestFn;bank?:BankAccount;mode?:'create'|'edit'|'balance';currency?:string;currencies?:string[];onClose:()=>void;onSaved:(bank:BankAccount)=>void}){
 const [name,setName]=useState(bank?.name??''),[bankName,setBankName]=useState(bank?.bankName??''),[tail,setTail]=useState(bank?.cardLastFour??'');
 const [balances,setBalances]=useState([{currency,openingBalance:'',openingOn:businessDate()}]);const [confirmed,setConfirmed]=useState(false),[key]=useState(newIdempotencyKey);const cache=useQueryClient();
 const save=useMutation({mutationFn:()=>request<BankAccount>(mode==='create'?'/api/bank-accounts':`/api/bank-accounts/${bank!.id}${mode==='balance'?'/balances':''}`,{method:mode==='edit'?'PATCH':'POST',headers:{'Idempotency-Key':key},body:mode==='balance'?balances[0]:mode==='edit'?{name,bankName,cardLastFour:tail}:{name,bankName,cardLastFour:tail,balances}}),onSuccess:async result=>{await Promise.all(['accounts','bank-accounts','dashboard','net-worth','accounting-history'].map(k=>cache.invalidateQueries({queryKey:[k]})));onSaved(result);}});
 return <ActionDialog open title={mode==='create'?'新建银行卡':mode==='edit'?'编辑银行卡':`添加 ${currency} 余额`} size="medium" draft={{name,bankName,tail,balances,confirmed}} busy={save.isPending} onClose={onClose}>
  <form className="feature-form bank-editor" onSubmit={e=>{e.preventDefault();e.stopPropagation();if(mode==='edit'||confirmed)save.mutate();}}><FormError compact error={save.error}/>
   {mode!=='balance'?<><label>账户名称<input name="name" required maxLength={80} value={name} onChange={e=>setName(e.target.value)} placeholder="例如：汇丰 One"/></label><div className="bank-editor-grid"><label>银行名称<input name="bankName" maxLength={80} value={bankName} onChange={e=>setBankName(e.target.value)}/></label><label>卡尾号（选填）<input name="cardLastFour" maxLength={4} pattern="[0-9]{4}" inputMode="numeric" value={tail} onChange={e=>setTail(e.target.value)}/></label></div></>:<h3>{bank?.name}</h3>}
   {mode!=='edit'&&<><div className="bank-balance-rows">{balances.map((row,i)=><div key={i} className="bank-balance-row"><label>币种<select aria-label={`币种 ${i+1}`} disabled={mode==='balance'} value={row.currency} onChange={e=>{setConfirmed(false);setBalances(b=>b.map((v,j)=>j===i?{...v,currency:e.target.value}:v));}}>{currencies.filter(c=>c===row.currency||!balances.some(b=>b.currency===c)).map(c=><option key={c}>{c}</option>)}</select></label><label>期初余额<input name={`balances.${i}.openingBalance`} aria-label={`${row.currency} 期初余额`} required inputMode="decimal" value={row.openingBalance} placeholder="0.00" onChange={e=>{setConfirmed(false);setBalances(b=>b.map((v,j)=>j===i?{...v,openingBalance:e.target.value}:v));}}/></label><label>起始日期<DateField required max={businessDate()} value={row.openingOn} onChange={e=>{setConfirmed(false);setBalances(b=>b.map((v,j)=>j===i?{...v,openingOn:e.target.value}:v));}}/></label>{mode==='create'&&balances.length>1&&<button type="button" className="text-action" onClick={()=>{setConfirmed(false);setBalances(b=>b.filter((_,j)=>j!==i));}}>移除</button>}</div>)}</div>
   {mode==='create'&&balances.length<currencies.length&&<button type="button" className="text-action" onClick={()=>{setConfirmed(false);setBalances(b=>[...b,{currency:currencies.find(c=>!b.some(r=>r.currency===c))!,openingBalance:'',openingOn:businessDate()}]);}}>添加币种</button>}
   <label className="switch-line"><input type="checkbox" required checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/>确认各币种实际期初余额与日期，零余额也需确认</label></>}
   <Button htmlType="submit" theme="solid" loading={save.isPending} disabled={mode!=='edit'&&!confirmed}>{mode==='edit'?'保存银行卡':'保存余额'}</Button>
  </form>
 </ActionDialog>;
}
