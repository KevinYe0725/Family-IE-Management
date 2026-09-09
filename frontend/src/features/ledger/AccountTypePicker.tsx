import { useId } from 'react';
import type { AccountType,WalletProvider } from '../../api/contracts';
import { AccountIcon } from './AccountIdentity';
export interface AccountClassification{type:AccountType;walletProvider:WalletProvider|''}
const options:Array<AccountClassification & {label:string}>=[
  {type:'CASH',walletProvider:'',label:'现金'}, {type:'BANK',walletProvider:'',label:'银行卡'},
  {type:'WALLET',walletProvider:'ALIPAY',label:'支付宝'},{type:'WALLET',walletProvider:'WECHAT',label:'微信'},
  {type:'WALLET',walletProvider:'OTHER',label:'其他钱包'},
];
export function AccountTypePicker({value,onChange,allowBank=true}:{value:AccountClassification;onChange:(value:AccountClassification)=>void;allowBank?:boolean}){
  const id=useId();
  const choices=value.type==='WALLET'&&!value.walletProvider?[...options,{type:'WALLET' as const,walletProvider:'' as const,label:'未细分电子钱包'}]:options;
  return <fieldset className="account-type-picker"><legend>账户分类</legend><div>{choices.filter(option=>allowBank||option.type!=='BANK').map(option=><label key={option.label}>
    <input type="radio" name={id} checked={value.type===option.type&&value.walletProvider===option.walletProvider} onChange={()=>onChange({type:option.type,walletProvider:option.walletProvider})}/>
    <span><AccountIcon account={{...option,walletProvider:option.walletProvider||null}}/><span>{option.label}</span></span>
  </label>)}</div></fieldset>;
}
