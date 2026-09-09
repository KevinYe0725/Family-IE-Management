import {useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import type {Account} from '../../api/contracts';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import {DateField} from '../../shared/DateField';
import {Drawer,FormError,type RequestFn} from '../common';
import {AccountTypePicker,type AccountClassification} from './AccountTypePicker';
export function FundingAccountCreator({currency,request,onCreated,onClose,presentation='drawer',dialogClassName}:{dialogClassName?:string;presentation?:'drawer'|'modal';currency:string;request:RequestFn;onCreated:(account:Account)=>void;onClose:()=>void}){
 const [draft,setDraft]=useState({name:'',openingBalance:'0.00',openingOn:businessDate(),confirmed:false});
 const [kind,setKind]=useState<AccountClassification>({type:'BANK',walletProvider:''});const [key]=useState(newIdempotencyKey);const cache=useQueryClient();
 const save=useMutation({mutationFn:()=>request<Account>('/api/accounts',{method:'POST',headers:{'Idempotency-Key':key},body:{name:draft.name,type:kind.type,walletProvider:kind.walletProvider||null,currency,openingBalance:draft.openingBalance,openingOn:draft.openingOn}}),onSuccess:async(account)=>{await cache.invalidateQueries({queryKey:['accounts']});onCreated(account);}});
 const validPlatform=currency==='CNY'||kind.type!=='WALLET'||!['ALIPAY','WECHAT'].includes(kind.walletProvider);
 return <Drawer className={dialogClassName} presentation={presentation} open title={`新建 ${currency} 资金账户`} draft={{...draft,...kind}} busy={save.isPending} onClose={onClose}>
  <form className="feature-form" onSubmit={e=>{e.preventDefault();if(draft.confirmed&&validPlatform)save.mutate();}}><FormError compact={presentation==="modal"} error={save.error}/>
   <label>资金账户名称<input required value={draft.name} onChange={e=>setDraft({...draft,name:e.target.value})}/></label>
   <AccountTypePicker value={kind} onChange={setKind}/><p>币种：{currency}</p>
   {!validPlatform&&<p role="alert">支付宝和微信余额仅支持人民币，请选择银行卡、现金或其他钱包。</p>}
   <label>期初余额<input required inputMode="decimal" value={draft.openingBalance} onChange={e=>setDraft({...draft,openingBalance:e.target.value,confirmed:false})}/></label>
   <label>账务起始日期<DateField required max={businessDate()} value={draft.openingOn} onChange={e=>setDraft({...draft,openingOn:e.target.value,confirmed:false})}/></label>
   <label className="switch-line"><input type="checkbox" checked={draft.confirmed} onChange={e=>setDraft({...draft,confirmed:e.target.checked})}/>确认实际期初余额与日期，零余额也需确认</label>
   <button type="submit" disabled={!draft.confirmed||!validPlatform||save.isPending}>保存并使用此账户</button>
  </form>
 </Drawer>;
}
