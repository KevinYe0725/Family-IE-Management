import {useEffect,useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {Info} from 'lucide-react';
import type {Account,Page} from '../../api/contracts';
import {DateField} from '../../shared/DateField';
import {PaginationControls,usePageRecovery} from '../../shared/pagination';
import {ActionDialog,QueryState,money,type RequestFn} from '../common';
import {AccountingHistory} from './accounting-flows';
import './cash-movements.scss';

export interface CashMovement {id:string;journalId:number;sourceType:string;sourceId:number;effectiveOn:string;accountId:number;accountName:string;currency:string;kind:'income'|'expense';amount:string;internalTransfer:boolean;description:string}
const labels:Record<string,string>={TRANSACTION:'日常收支',CASH_OPENING:'期初余额',LOAN_DISBURSEMENT:'贷款到账',LOAN_FINANCED_PURCHASE:'贷款购置首付款',LOAN_PAYMENT:'计划还款',LOAN_PREPAYMENT:'提前还款',ASSET_ACQUISITION:'资产购入',ASSET_DISPOSAL:'资产出售',INVESTMENT_TRADE:'投资交易',CASH_TRANSFER:'账户互转',FX_TRANSFER:'币种互转'};
export function CashMovementsPanel({request,accounts,month,onMonth}:{request:RequestFn;accounts:Account[];month:string;onMonth:(month:string)=>void}){
 const [account,setAccount]=useState(''),[bank,setBank]=useState(''),[kind,setKind]=useState(''),[page,setPage]=useState(0);
 const [selected,setSelected]=useState<CashMovement|null>(null);
 const params=new URLSearchParams({month,page:String(page),size:'20'});
 if(account)params.set('accountId',account);if(bank)params.set('bankAccountId',bank);if(kind)params.set('kind',kind);
 const query=useQuery({queryKey:['cash-movements',params.toString()],queryFn:()=>request<Page<CashMovement>>(`/api/cash-movements?${params}`,{responseType:'page'})});
 useEffect(()=>setPage(0),[month,bank,account,kind]);usePageRecovery(page,query.data,setPage);
 const banks=[...new Map(accounts.filter(a=>a.bankAccountId).map(a=>[a.bankAccountId!,a.bankAccountName??a.name])).entries()];
 return <section aria-label="全部资金流水" className="cash-movements">
  <div className="filter-bar"><label>月份<DateField mode="month" allowClear={false} value={month} onChange={e=>{if(e.target.value)onMonth(e.target.value);}}/></label><label>银行卡<select value={bank} onChange={e=>{setBank(e.target.value);setAccount('');}}><option value="">全部</option>{banks.map(([id,name])=><option key={id} value={id}>{name}</option>)}</select></label><label>账户<select value={account} onChange={e=>setAccount(e.target.value)}><option value="">全部</option>{accounts.filter(a=>!bank||a.bankAccountId===Number(bank)).map(a=><option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>流向<select value={kind} onChange={e=>setKind(e.target.value)}><option value="">全部</option><option value="income">流入</option><option value="expense">流出</option></select></label><span className="cash-movement-hint" title="现金进出不等于收入费用。包含放款、还款、资产买卖及互转；金额按原币展示。"><Info size={17} aria-label="资金流水口径"/></span></div>
  <QueryState loading={query.isLoading} error={query.error} empty={!query.data?.items.length} emptyTitle="暂无资金流水" emptyDetail="现金变化入账后会出现在这里。">
   <div className="cash-movement-table"><table><thead><tr><th>日期</th><th>业务</th><th>账户</th><th>资金变动</th><th>详情</th></tr></thead><tbody>{query.data?.items.map(row=><tr key={row.id}><td>{row.effectiveOn}</td><td><strong>{labels[row.sourceType]??row.sourceType}</strong>{row.internalTransfer&&<span className="cash-internal">内部转移</span>}{row.description&&<span className="cash-description">{row.description}</span>}</td><td>{row.accountName}</td><td className={row.kind==='income'?'cash-in':'cash-out'}>{row.kind==='income'?'+':'−'}{money(row.amount,row.currency)}</td><td><button type="button" className="text-action" aria-label="查看入账轨迹" onClick={()=>setSelected(row)}>查看</button></td></tr>)}</tbody></table></div>
   <PaginationControls page={page} totalPages={query.data?.totalPages??0} hasNext={query.data?.hasNext??false} onPageChange={setPage} label="资金流水"/>
  </QueryState>
  {selected&&<ActionDialog open title="资金流水详情" size="wide" onClose={()=>setSelected(null)}><strong className="ledger-detail-amount">{selected.kind==='income'?'+':'−'}{money(selected.amount,selected.currency)}</strong><p>{selected.accountName} · {labels[selected.sourceType]??selected.sourceType}</p><AccountingHistory compact request={request} source={{sourceType:selected.sourceType,sourceId:selected.sourceId}}/></ActionDialog>}
 </section>;
}
