import {useRef,useState,useEffect} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import {ApiError} from '../../api/client';
import type {Account,Asset} from '../../api/contracts';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import {DateField} from '../../shared/DateField';
import {useDraftProtection} from '../../shared/draft-guard';
import {BankAccountPicker} from '../ledger/BankAccountPicker';
import {ActionDialog,FormError,QueryState,money,type RequestFn} from '../common';
import type {SaleDraft,SaleLoan,SalePreview,SaleResult} from './asset-sale';
import './asset-sale.scss';

export function AssetSaleReceipt({preview:p}:{preview:SalePreview}){
 return <section className="asset-sale-receipt" aria-label="出售结算明细">
  <div className="asset-sale-net"><span>{Number(p.netSettlement)<0?'本次需补足':'出售结算净额'}</span><strong>{money(p.netSettlement)}</strong></div>
  <dl className="asset-sale-breakdown"><div><dt>出售总价</dt><dd>{money(p.proceeds)}</dd></div><div><dt>出售费用</dt><dd>{money(p.fee)}</dd></div><div><dt>偿还本金</dt><dd>{money(p.totalPrincipal)}</dd></div><div><dt>偿还利息</dt><dd>{money(p.totalInterest)}</dd></div></dl>
  {p.route==='DIRECT'&&p.loans.length>0&&<p className="asset-sale-route-note">买方直接代偿，账户仅记录实际收取或补足的差额。</p>}
  {p.balances.map(a=><article className="asset-sale-account" key={a.accountId}><h3>{a.accountName}</h3><dl><div><dt>原余额</dt><dd>{money(a.before,a.currency)}</dd></div><div><dt>本次变动</dt><dd>{money(a.change,a.currency)}</dd></div><div><dt>结算后余额</dt><dd><strong>{money(a.after,a.currency)}</strong></dd></div></dl></article>)}
  {p.loans.map(l=><article className="asset-sale-loan-result" key={l.loanId}><h3>{l.name}<span>{l.mode==='PAYOFF'?'本次结清':'部分还款'}</span></h3>{l.mode==='PARTIAL'&&l.additionalPrincipal!=null?<p>到期本金 {money(l.duePrincipal)} · 到期利息 {money(l.dueInterest)} · 额外还本 {money(l.additionalPrincipal)}</p>:<p>本金 {money(l.principal)} · 利息 {money(l.interest)}</p>}<p>剩余本金 <strong>{money(l.remainingPrincipal)}</strong>{l.remainingTerm!=null&&<> · 剩余 {l.remainingTerm} 期</>}{l.nextPaymentAmount!=null&&<> · 下期 {money(l.nextPaymentAmount)}</>}</p></article>)}
  {p.retainedLoans.length>0&&<section className="asset-sale-retained"><h3>继续偿还的贷款</h3>{p.retainedLoans.map(l=><p key={l.loanId}>{l.name} <strong>{money(l.remainingPrincipal)}</strong></p>)}</section>}
  <details><summary>资产账面损益</summary><p>处置前价值 {money(p.bookValue)} · 处置损益 {money(p.bookGain)}</p></details>
 </section>;
}

export function AssetSaleDialog({asset,accounts,accountsReady,request,onClose}:{asset:Asset;accounts:Account[];accountsReady:boolean;request:RequestFn;onClose:()=>void}){
 const [draft,setDraft]=useState<SaleDraft>(()=>({disposedOn:businessDate(),proceeds:'',fee:'0',cashAccountId:null,repaymentAccountId:null,route:'VIA_ACCOUNT',repayments:[],retainUnselectedLoans:false}));
 const [review,setReview]=useState<{draft:SaleDraft;preview:SalePreview;key:string}|null>(null);
 const [result,setResult]=useState<SaleResult|null>(null),[error,setError]=useState<unknown>(null),[busy,setBusy]=useState(false),[uncertain,setUncertain]=useState(false);
 const cache=useQueryClient();
 const refreshInputs=()=>{void cache.invalidateQueries({queryKey:['assets','financing',asset.id]});void cache.invalidateQueries({queryKey:['accounts']});};
 const release=useRef<(()=>void)|undefined>(undefined),inFlight=useRef(false);
 useDraftProtection({active:uncertain,busy:uncertain,sessionKey:`sale-recovery:${asset.id}`});
 useEffect(()=>()=>release.current?.(),[]);
 const related=useQuery({queryKey:['assets','financing',asset.id],queryFn:()=>request<{loans:Array<{loanId:number;name:string;status:string;remainingPrincipal:string}>}>(`/api/assets/${asset.id}/loans`)});
 const loans=(related.data?.loans??[]).filter(l=>l.status==='ACTIVE');
 const retained=loans.filter(l=>!draft.repayments.some(r=>r.loanId===l.loanId));
 const change=(patch:Partial<SaleDraft>)=>setDraft(d=>({...d,...patch}));
 const setLoan=(id:number,patch:Partial<SaleLoan>|null)=>setDraft(d=>({...d,repayments:patch===null?d.repayments.filter(r=>r.loanId!==id):[...d.repayments.filter(r=>r.loanId!==id),{loanId:id,mode:'PAYOFF',...d.repayments.find(r=>r.loanId===id),...patch}],retainUnselectedLoans:false}));
 async function quote(){
  if(inFlight.current)return;inFlight.current=true;setBusy(true);setError(null);
  const snapshot:SaleDraft=JSON.parse(JSON.stringify({...draft,repayments:draft.repayments.map(r=>r.mode==='PAYOFF'?{loanId:r.loanId,mode:r.mode,interestAmount:r.interestAmount}:{loanId:r.loanId,mode:r.mode,additionalPrincipal:r.additionalPrincipal,strategy:r.strategy,targetPeriods:r.strategy==='ADJUST_TERM'?r.targetPeriods:undefined}),repaymentAccountId:draft.route==='DIRECT'?draft.cashAccountId:draft.repaymentAccountId??draft.cashAccountId}));
  try{const preview=await request<SalePreview>(`/api/assets/${asset.id}/sale-preview`,{method:'POST',body:snapshot});setReview({draft:snapshot,preview,key:newIdempotencyKey()});}catch(e){setError(e);}finally{setBusy(false);inFlight.current=false;}
 }
 async function confirm(){
  if(!review||!review.preview.canConfirm||inFlight.current)return;inFlight.current=true;setBusy(true);setError(null);
  release.current??=request.beginRecoverableOperation?.();
  try{setResult(await request<SaleResult>(`/api/assets/${asset.id}/sale`,{method:'POST',headers:{'Idempotency-Key':review.key},body:{draft:review.draft,planToken:review.preview.planToken}}));setUncertain(false);release.current?.();release.current=undefined;}
  catch(e){setError(e);const definitive=e instanceof ApiError&&[400,404,409,422].includes(e.status);setUncertain(!definitive);if(definitive){setReview(null);refreshInputs();release.current?.();release.current=undefined;}}
  finally{setBusy(false);inFlight.current=false;}
 }
 return <ActionDialog open title={`${asset.name} · 记录出售`} size="wide" className="asset-sale-dialog" busy={busy} draft={result?undefined:draft} sessionKey={`sale:${asset.id}`} onClose={()=>{if(!busy&&!uncertain)onClose();}}>
  <FormError error={error}/>
  {result?<><h2 role="status">出售已记录</h2><AssetSaleReceipt preview={result.preview}/><Button theme="solid" type="primary" onClick={onClose}>完成</Button></>:review?<>
   <AssetSaleReceipt preview={review.preview}/>
   <p>确认后资产归档，出售记录不可修改；保留的贷款继续偿还。</p>
   {review.preview.blockers.length>0&&<div role="alert" className="asset-sale-blockers">{review.preview.blockers.map((b,i)=><p key={i}>{b}</p>)}</div>}
   {uncertain&&<p role="status">结果尚未确认。请重试确认，将使用同一笔结算，不会重复记账。</p>}
   {uncertain&&error instanceof ApiError&&[401,403].includes(error.status)&&<p role="status">请保留本页，在另一标签页使用同一账号恢复登录，再返回重试；如仍无权限，请核对家庭角色及还款人设置。</p>}
   <div className="asset-sale-actions">{!uncertain&&<Button disabled={busy} onClick={()=>{setReview(null);setError(null);refreshInputs();}}>返回修改</Button>}<Button theme="solid" type="primary" loading={busy} disabled={!review.preview.canConfirm} onClick={confirm}>{uncertain?'重试确认':'确认记录出售'}</Button></div>
  </>:<form className="feature-form" onSubmit={e=>{e.preventDefault();void quote();}}><fieldset disabled={busy}>
   <div className="asset-sale-fields"><label>出售总价<input required inputMode="decimal" value={draft.proceeds} onChange={e=>change({proceeds:e.target.value})}/></label><label>从出售款扣除的费用<input required inputMode="decimal" value={draft.fee} onChange={e=>change({fee:e.target.value})}/></label><label>实际出售日期<DateField required max={businessDate()} value={draft.disposedOn} onChange={e=>change({disposedOn:e.target.value})}/></label></div>
   <QueryState loading={related.isLoading} error={related.error}><div className="asset-sale-loan-choices">{loans.map(l=>{const chosen=draft.repayments.find(r=>r.loanId===l.loanId);return <section key={l.loanId}><header><h3>{l.name}</h3><span>剩余本金 {money(l.remainingPrincipal)}</span></header><label>贷款处理<select aria-label={`${l.name}处理方式`} value={chosen?.mode??'KEEP'} onChange={e=>setLoan(l.loanId,e.target.value==='KEEP'?null:{mode:e.target.value as SaleLoan['mode'],additionalPrincipal:'',strategy:'REDUCE_PAYMENT'})}><option value="KEEP">保留贷款，继续偿还</option><option value="PAYOFF">一次结清</option><option value="PARTIAL">部分提前还款</option></select></label>{chosen?.mode==='PARTIAL'&&<div className="asset-sale-fields"><label>额外偿还本金<input required inputMode="decimal" value={chosen.additionalPrincipal??''} onChange={e=>setLoan(l.loanId,{additionalPrincipal:e.target.value})}/></label><label>剩余还款安排<select value={chosen.strategy} onChange={e=>setLoan(l.loanId,{strategy:e.target.value as SaleLoan['strategy']})}><option value="REDUCE_PAYMENT">减少月供，期数不变</option><option value="REDUCE_TERM">缩短期限，按原月供上限</option><option value="ADJUST_TERM">自选更短期数</option></select></label>{chosen.strategy==='ADJUST_TERM'&&<label>剩余期数<input required type="number" min="1" step="1" value={chosen.targetPeriods??''} onChange={e=>setLoan(l.loanId,{targetPeriods:e.target.value?Number(e.target.value):undefined})}/></label>}<p>到期未还本息将一并结算；预览中核对总付款。</p></div>}{chosen?.mode==='PAYOFF'&&<label>实际结清利息（选填）<input inputMode="decimal" placeholder="默认按到期未还利息计算" value={chosen.interestAmount??''} onChange={e=>setLoan(l.loanId,{interestAmount:e.target.value||undefined})}/></label>}</section>;})}</div></QueryState>
   {draft.repayments.length>0&&<label>款项如何结算<select value={draft.route} onChange={e=>change({route:e.target.value as SaleDraft['route']})}><option value="VIA_ACCOUNT">出售款先到账，再从账户还贷</option><option value="DIRECT">买方直接代偿，只收取或补足差额</option></select></label>}
   <BankAccountPicker label={draft.route==='DIRECT'?'差额收付账户':'收款账户'} required={draft.repayments.length>0||Number(draft.proceeds)!==0||Number(draft.fee)!==0} currency="CNY" accountsReady={accountsReady} accounts={accounts} request={request} value={draft.cashAccountId==null?'':String(draft.cashAccountId)} onChange={id=>change({cashAccountId:id?Number(id):null})}/>
   {draft.route==='VIA_ACCOUNT'&&draft.repayments.length>0&&<BankAccountPicker label="还款账户（留空使用收款账户）" required={false} currency="CNY" accountsReady={accountsReady} accounts={accounts} request={request} value={draft.repaymentAccountId==null?'':String(draft.repaymentAccountId)} onChange={id=>change({repaymentAccountId:id?Number(id):null})}/>}
   {retained.length>0&&<label className="asset-sale-consent"><input type="checkbox" required checked={draft.retainUnselectedLoans} onChange={e=>change({retainUnselectedLoans:e.target.checked})}/>我确认出售后仍需偿还以上保留的贷款</label>}
   <div className="asset-sale-actions"><Button htmlType="submit" theme="solid" type="primary" loading={busy} disabled={related.isLoading||!!related.error}>预览结算</Button></div>
  </fieldset></form>}
 </ActionDialog>;
}
