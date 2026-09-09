import {useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import type {Page,Transaction} from '../../api/contracts';
import {ActionDialog,QueryState,money,type RequestFn} from '../common';
import {PaginationControls} from '../../shared/pagination';

export function TransactionDetailsDialog({item,onClose,onHistory}:{item:Transaction;onClose:()=>void;onHistory:()=>void}){
 const nonCash=item.cashImpact===false;
 return <ActionDialog open title={nonCash?'非现金还款详情':'收支详情'} onClose={onClose}><strong className="ledger-detail-amount">{nonCash?'':item.kind==='income'?'+':'−'}{money(item.amount,item.currency)}</strong>{nonCash&&<p role="status">买方代偿，本笔还款不另行扣减银行账户余额。</p>}<dl className="ledger-detail-fields">{[['日期',item.occurredOn],['分类',item.categoryName],[nonCash?'结算方式':'账户',nonCash?'买方代偿（非现金）':item.accountName],['成员',item.memberName],['记录人',item.createdByName],['商家',item.merchant],['地点',item.location],['备注',item.note]].filter(([,v])=>v).map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>{item.principalAmount!=null&&<p>本金 {money(item.principalAmount,item.currency)} · 利息 {money(item.interestAmount,item.currency)}</p>}<button className="text-action" disabled={!['MANUAL','RECURRING'].includes(item.sourceType)&&item.sourceId==null} onClick={onHistory}>账务历史</button></ActionDialog>;
}
export function BankTransactionsDialog({request,bankId,onClose}:{request:RequestFn;bankId:number;onClose:()=>void}){
 const [page,setPage]=useState(0);
 const data=useQuery({queryKey:['transactions','bank-detail',bankId,page],queryFn:()=>request<Page<Transaction>>(`/api/transactions?bankAccountId=${bankId}&page=${page}&size=20`,{responseType:'page'})});
 return <ActionDialog open title="银行卡流水" size="wide" onClose={onClose}><QueryState loading={data.isLoading} error={data.error} empty={!data.data?.items.length}><div className="ledger-detail-table"><table><thead><tr><th>日期</th><th>分类</th><th>成员</th><th>金额</th><th>备注</th></tr></thead><tbody>{data.data?.items.map(row=><tr key={row.id}><td>{row.occurredOn}</td><td>{row.categoryName}</td><td>{row.memberName}</td><td>{row.cashImpact===false?'':row.kind==='expense'?'−':'+'}{money(row.amount,row.currency)}{row.cashImpact===false&&<small>买方代偿（非现金）</small>}</td><td>{row.note||row.merchant||'—'}</td></tr>)}</tbody></table></div><PaginationControls page={page} totalPages={data.data?.totalPages??0} hasNext={data.data?.hasNext??false} onPageChange={setPage} label="银行卡流水"/></QueryState></ActionDialog>;
}
