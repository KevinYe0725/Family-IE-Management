import {cents,decimal} from '../accounting';
import {money} from '../common';
import './loan-funding.scss';
export function ownContribution(purchase:string|undefined|null,principal:string):string|null{
 const p=cents(purchase),l=cents(principal);return p!=null&&l!=null&&p>=l?decimal(p-l):null;
}
export function LoanPurchaseSummary({purchase,principal}:{purchase:string;principal:string}){
 const own=ownContribution(purchase,principal);
 if(!purchase||!principal)return null;
 if(own==null)return <p role="alert">完整购置金额不能小于贷款本金。</p>;
 return <section className="loan-funding-preview" aria-label="购置资金核对"><div><span>完整购置价</span><strong>{money(purchase)}</strong></div><div><span>贷款部分</span><strong>{money(principal)}</strong></div><div><span>首付款</span><strong>{money(own)}</strong></div></section>;
}
