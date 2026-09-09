import {cents,decimal} from '../accounting';
import {money} from '../common';
import './loan-funding.scss';
export function LoanReceiptSummary({principal,receipt}:{principal:string;receipt:string}){
 const p=cents(principal),r=cents(receipt);
 if(p==null||r==null)return null;
 if(r<=0n||r>p)return <p role="alert">到账金额应大于零且不超过本金。</p>;
 return <section className="loan-funding-preview" aria-label="放款核对"><div><span>借款本金</span><strong>{money(principal)}</strong></div><div><span>实际到账</span><strong>{money(receipt)}</strong></div><div><span>预扣费用</span><strong>{money(decimal(p-r))}</strong></div>{p>r&&<p>预扣费用按所选还款分类记账，贷款本金不减少。</p>}</section>;
}
