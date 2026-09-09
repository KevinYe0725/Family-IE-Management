import {useTransientNotice} from '../../shared/useTransientNotice';
import {useEffect,useState} from 'react';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import Modal from '@douyinfe/semi-ui/lib/es/modal';
import {InvestmentButton as Button,InvestmentActions} from './investment-ui';
import {CalendarClock,ArrowRight,Repeat2,X} from 'lucide-react';
import type {Account,InvestmentAccount,Membership,Page} from '../../api/contracts';
import {DateField} from '../../shared/DateField';
import {businessDate,newIdempotencyKey} from '../../shared/runtime';
import {readAllPages} from '../../shared/pagination';
import {PaymentPreview,cents,tradeCash,unitPrice,useFundsRefresh} from '../accounting';
import {ConfirmDialog,FormError,QueryState,StatusTag,dateText,money,type RequestFn} from '../common';
import {TradeStockPicker,securityCurrency,type SecuritySelection} from './TradeStockPicker';
import {useInvestmentPlans,frequencyLabel,quantityText,planTarget,validPlanQuantity,type InvestmentPlan,type InvestmentPlanOccurrence,type PlanFrequency} from './investment-plans';
import './investment-plans.scss';

export type InvestmentPlanSupportState={loading:boolean;error:unknown;retry:()=>void};
type Props={request:RequestFn;manager:boolean;accounts:InvestmentAccount[];cashAccounts:Account[];supportState?:InvestmentPlanSupportState;createRequest?:number;onCreateHandled?:()=>void};
export function InvestmentPlansPanel({request,manager,accounts,cashAccounts,supportState,createRequest=0,onCreateHandled}:Props){
 const [planPage,setPlanPage]=useState(0),[occurrencePage,setOccurrencePage]=useState(0);
 const query=useInvestmentPlans(request,planPage,occurrencePage),cache=useQueryClient();
 const [editor,setEditor]=useState<InvestmentPlan|'new'|null>(null);
 const [payment,setPayment]=useState<InvestmentPlanOccurrence|null>(null);
 const [skip,setSkip]=useState<InvestmentPlanOccurrence|null>(null),[reason,setReason]=useState('');
 const [ending,setEnding]=useState<InvestmentPlan|null>(null);
 const {message:notice,show:setNotice,dismiss:dismissNotice}=useTransientNotice();
 const refresh=async()=>{await Promise.all(['investment-plans','notifications','accounts','portfolio','investment-trades','dashboard','net-worth'].map(key=>cache.invalidateQueries({queryKey:[key]})));};
 const action=useMutation({mutationFn:({path,body}:{path:string;body:unknown})=>request(path,{method:'POST',body,headers:{'Idempotency-Key':newIdempotencyKey()}}),onSuccess:async()=>{setSkip(null);setEnding(null);await refresh();setNotice('已更新，未确认的实际成交不会自动记账。');}});
 const supportUnavailable=Boolean(supportState?.loading||supportState?.error);
 useEffect(()=>{if(createRequest&&manager&&!supportUnavailable){setEditor('new');onCreateHandled?.();}},[createRequest,manager,supportUnavailable]);
 const pending=query.data?.occurrences?.filter(o=>o.state==='PENDING')??[];
 const history=query.data?.occurrences?.filter(o=>o.state!=='PENDING')??[];
 return <section className="investment-plans" aria-label="定投计划">
  <header className="investment-plans-heading"><div><h2>让计划有节奏</h2><p>到期提醒，成交后由你确认。</p></div>{manager&&<Button theme="solid" disabled={supportUnavailable} onClick={()=>setEditor('new')}>新建定投计划</Button>}</header>
  {supportState?.error?<div className="plan-support-state" role="alert"><span>{supportState.error instanceof Error?supportState.error.message:'定投所需的投资账户或资金账户暂时无法读取。'}</span><button type="button" className="text-action" onClick={supportState.retry}>重试定投账户数据</button></div>:supportState?.loading?<p className="plan-support-state" role="status">正在读取定投所需的投资账户和资金账户…</p>:null}
  {notice&&<p className="investment-saved" role="status">{notice}<button type="button" className="text-action" aria-label="关闭成功提示" onClick={dismissNotice}><X size={16}/></button></p>}<FormError compact error={action.error}/>
  <QueryState loading={query.isLoading} error={query.error} empty={false}>
   <div className="plan-section-heading"><h3>待确认</h3><span>{query.data?.pendingCount??pending.length} 期</span></div>
   {!pending.length?<div className="plan-quiet"><CalendarClock size={24} aria-hidden="true"/><span>当前页没有待确认定投，到期后会在提醒中心通知负责人。</span></div>:pending.map(item=><article className="plan-due" key={item.id}>
    <div><span className="plan-symbol">{item.symbol} · {dateText(item.dueOn)}</span><h4>{item.securityName}</h4><p>{item.planName} · {item.accountName}</p>{item.remindAt&&new Date(item.remindAt)>new Date()&&<p>下次提醒 {new Date(item.remindAt).toLocaleString('zh-CN')}</p>}</div>
    <div className="plan-due-amount"><span>本期计划</span><strong>{item.quantity!=null?planTarget(item):`旧预算 ${money(item.amount,item.currency)}`}</strong></div>
    {manager&&<div className="plan-due-actions"><Button variant="secondary" disabled={action.isPending||supportUnavailable} onClick={()=>setPayment(item)}>确认已成交</Button><InvestmentActions label={`${item.securityName}本期操作`} disabled={action.isPending} actions={[{label:'跳过本期',onClick:()=>{action.reset();setReason('');setSkip(item);}},{label:'两小时后提醒',onClick:()=>action.mutate({path:`/api/investment-plans/occurrences/${item.id}/snooze`,body:{option:'TWO_HOURS'}})},{label:'明天提醒',onClick:()=>action.mutate({path:`/api/investment-plans/occurrences/${item.id}/snooze`,body:{option:'TOMORROW'}})}]}/></div>}
   </article>)}
   <div className="plan-section-heading"><h3>我的计划</h3></div>
   {!query.data?.plans?.length?<div className="plan-quiet"><Repeat2 size={24} aria-hidden="true"/><span>选择证券、股数和周期，开始第一个提醒计划。</span></div>:<div className="plan-cards">{query.data.plans.map(plan=><article key={plan.id}>
    <header><span className="plan-symbol">{plan.symbol}</span><StatusTag tone="neutral">{plan.state==='ENDED'?'已结束':plan.quantity==null?'需设置股数':plan.state==='ACTIVE'?'进行中':'已暂停'}</StatusTag></header><h3>{plan.name}</h3><p>{plan.securityName} · {plan.accountName}</p><div className="plan-card-amount"><strong>{planTarget(plan)}</strong><span> / {frequencyLabel[plan.frequency]}</span></div><p>下次提醒 {plan.state==='ACTIVE'?dateText(plan.nextDueOn):'—'}</p>{plan.quantity==null&&plan.state!=='ENDED'&&<p>旧金额计划已暂停，请编辑并设置股数。</p>}
    {manager&&plan.state!=='ENDED'&&<footer><Button variant="quiet" size="small" disabled={supportUnavailable} onClick={()=>setEditor(plan)}>编辑计划</Button><InvestmentActions label={`${plan.name}计划操作`} disabled={action.isPending} actions={[...(plan.quantity!=null?[{label:plan.state==='ACTIVE'?'暂停':'恢复',onClick:()=>action.mutate({path:`/api/investment-plans/${plan.id}/state`,body:{state:plan.state==='ACTIVE'?'PAUSED':'ACTIVE'}})}]:[]),{label:'结束计划',danger:true,onClick:()=>{action.reset();setEnding(plan);}}]}/></footer>}
   </article>)}</div>}
   <div className="plan-pagination"><button disabled={planPage===0} onClick={()=>setPlanPage(p=>p-1)}>上一页计划</button><button disabled={!query.data?.hasMorePlans} onClick={()=>setPlanPage(p=>p+1)}>下一页计划</button></div>
   <div className="plan-section-heading"><h3>执行记录</h3><span>与真实成交关联</span></div>
   {history.length?<div className="responsive-data"><table><thead><tr><th>提醒日期</th><th>证券</th><th>计划股数</th><th>确认时股数</th><th>确认时金额</th><th>结果</th></tr></thead><tbody>{history.map(item=><tr key={item.id}><td>{dateText(item.dueOn)}</td><td>{item.securityName}</td><td>{item.quantity!=null?planTarget(item):`旧预算 ${money(item.amount,item.currency)}`}</td><td>{item.actualQuantity!=null?`${quantityText(item.actualQuantity)} 股`:'—'}</td><td>{money(item.actualAmount,item.currency)}</td><td><OccurrenceResult item={item}/></td></tr>)}</tbody></table></div>:<p className="plan-quiet">当前页没有已处理记录。</p>}
   <div className="plan-pagination"><button disabled={occurrencePage===0} onClick={()=>setOccurrencePage(p=>p-1)}>上一页期次</button><button disabled={!query.data?.hasMoreOccurrences} onClick={()=>setOccurrencePage(p=>p+1)}>下一页期次</button></div>
  </QueryState>
  {editor&&<PlanEditor request={request} plan={editor==='new'?undefined:editor} accounts={accounts} cashAccounts={cashAccounts} onClose={()=>setEditor(null)} onSaved={async()=>{setEditor(null);setNotice('提醒计划已保存；不会自动买入或扣款。');await refresh();}}/>}
  {payment&&<PlanConfirmation request={request} occurrence={payment} accounts={accounts} cashAccounts={cashAccounts} onClose={()=>setPayment(null)} onSaved={async()=>{setPayment(null);setNotice('实际成交已记账。');await refresh();}}/>}
  <ConfirmDialog open={Boolean(ending)} title="结束定投计划" detail={<><p>结束后不再生成新期次，已有待确认事项与成交历史仍保留。</p><FormError compact error={action.error}/></>} confirmLabel="结束计划" loading={action.isPending} onClose={()=>setEnding(null)} onConfirm={()=>ending&&action.mutate({path:`/api/investment-plans/${ending.id}/state`,body:{state:'ENDED'}})}/>
  {skip&&<Modal visible title="跳过本期定投" footer={null} maskClosable={false} onCancel={()=>{if(!action.isPending)setSkip(null);}} className="plan-modal investment-dialog"><form className="feature-form" onSubmit={e=>{e.preventDefault();action.mutate({path:`/api/investment-plans/occurrences/${skip.id}/skip`,body:{reason}});}}><p>只跳过 {dateText(skip.dueOn)} 这一期，不扣款，下一期照常提醒。</p><FormError compact error={action.error}/><label>原因（可选）<input maxLength={200} value={reason} onChange={e=>setReason(e.target.value)}/></label><Button htmlType="submit" theme="solid" loading={action.isPending}>确认跳过</Button></form></Modal>}
 </section>;
}

function planSecurity(plan?:InvestmentPlan):SecuritySelection|null{
 const security=plan?.security;
 return security&&security.id===plan.securityId&&Boolean(security.name?.trim()&&security.tsCode?.trim()&&security.market?.trim()&&security.currency?.trim())?security:null;
}
function PlanEditor({request,plan,accounts,cashAccounts,onClose,onSaved}:Omit<Props,'manager'|'supportState'>&{plan?:InvestmentPlan;onClose:()=>void;onSaved:()=>Promise<void>}){
 const [key]=useState(newIdempotencyKey);
 const identityMissing=Boolean(plan&&!planSecurity(plan));
 const [security,setSecurity]=useState<SecuritySelection|null>(()=>planSecurity(plan));
 const [name,setName]=useState(plan?.name??''),[quantity,setQuantity]=useState(quantityText(plan?.quantity)),[frequency,setFrequency]=useState<PlanFrequency>(plan?.frequency??'MONTHLY');
 const [firstDueOn,setFirstDueOn]=useState(plan?.firstDueOn??businessDate()),[accountId,setAccountId]=useState(String(plan?.accountId??'')),[assignedUserId,setAssignedUserId]=useState(String(plan?.assignedUserId??''));
 const members=useQuery({queryKey:['memberships','all-options'],queryFn:()=>readAllPages(p=>request<Page<Membership>>(`/api/family/memberships?page=${p}&size=50`,{responseType:'page'}))});
 const currency=security?securityCurrency(security):plan?.currency??'CNY',account=accounts.find(a=>String(a.id)===accountId),cash=cashAccounts.find(a=>a.id===account?.fundingAccountId);
 const valid=!identityMissing&&Boolean(security&&account?.currency===currency&&cash?.currency===currency&&cash.openingConfirmed&&cash.openingOn&&!cash.archivedAt&&validPlanQuantity(quantity)&&firstDueOn&&assignedUserId);
 const save=useMutation({mutationFn:()=>request(plan?`/api/investment-plans/${plan.id}`:'/api/investment-plans',{method:plan?'PATCH':'POST',headers:{'Idempotency-Key':key},body:{name:name.trim()||`${security?.name}定投`,accountId:Number(accountId),securityId:security?.id,quantity,frequency,firstDueOn,assignedUserId:Number(assignedUserId)}}),onSuccess:onSaved});
 return <Modal visible title={plan?'编辑定投计划':'创建定投计划'} width={900} footer={null} maskClosable={false} closeOnEsc={!save.isPending} onCancel={()=>{if(!save.isPending)onClose();}} className="plan-modal investment-dialog">
  <form onSubmit={e=>{e.preventDefault();if(valid)save.mutate();}}><div className="plan-dialog-grid"><fieldset disabled={save.isPending} className="feature-form plan-settings"><FormError compact error={save.error||members.error}/>
   {identityMissing&&<p role="alert">该计划缺少完整的证券身份，无法安全编辑。请关闭后刷新定投计划再重试。</p>}
   <TradeStockPicker request={request} value={security} disabled={save.isPending||identityMissing} onChange={value=>{setSecurity(value);if(value&&account?.currency!==securityCurrency(value))setAccountId('');}}/>
   <label className="plan-amount-input">每期计划股数<div><input aria-label="每期计划股数" name="quantity" required inputMode="decimal" value={quantity} onChange={e=>setQuantity(e.target.value)}/><span>股</span></div></label>
   <fieldset className="plan-frequency"><legend>提醒周期</legend>{(Object.keys(frequencyLabel) as PlanFrequency[]).map(f=><button type="button" aria-pressed={frequency===f} key={f} onClick={()=>setFrequency(f)}>{frequencyLabel[f]}</button>)}</fieldset>
   <label>首次提醒日期<DateField name="firstDueOn" required min={plan?undefined:businessDate()} value={firstDueOn} onChange={e=>setFirstDueOn(e.target.value)}/></label>
   {frequency==='MONTHLY'&&Number(firstDueOn.slice(-2))>28&&<p>没有对应日期的月份，在当月最后一天提醒。</p>}
   <label>投资账户<select name="accountId" required value={accountId} onChange={e=>setAccountId(e.target.value)}><option value="">选择同币种投资账户</option>{accounts.filter(a=>a.status==='ACTIVE'&&a.currency===currency).map(a=><option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
   {!accounts.some(a=>a.status==='ACTIVE'&&a.currency===currency)&&<p>请先<a href="/workspace/investments?tab=accounts">创建 {currency} 投资账户并关联现金账户</a>，然后再设置计划。</p>}
   <label>提醒负责人<select name="assignedUserId" required value={assignedUserId} onChange={e=>setAssignedUserId(e.target.value)}><option value="">选择家庭成员</option>{members.data?.filter(m=>m.status==='ACTIVE').map(m=><option value={m.userId} key={m.userId}>{m.displayName}</option>)}</select></label>
   <label>计划名称（可选）<input name="name" maxLength={80} placeholder={security?`${security.name}定投`:'例如：长期积累'} value={name} onChange={e=>setName(e.target.value)}/></label>
  </fieldset><aside className="plan-summary"><span className="plan-summary-label">计划摘要</span><h3>{security?.name??'选择一只证券'}</h3><strong className="plan-summary-amount">{quantity?`${quantityText(quantity)} 股`:'—'}</strong><dl><div><dt>频率</dt><dd>{frequencyLabel[frequency]}</dd></div><div><dt>首次提醒</dt><dd>{dateText(firstDueOn)}</dd></div><div><dt>扣款现金账户</dt><dd>{cash?.name??'随投资账户关联'}</dd></div><div><dt>当前可用现金</dt><dd>{money(cash?.availableBalance,currency)}</dd></div></dl>
   <div className="plan-confirmation-path"><CalendarClock size={20}/><span>到期提醒</span><ArrowRight size={16}/><span>确认成交后记账</span></div>
   <p>按实际成交股数、价格和费用扣减关联现金账户；创建计划不会扣款。</p>{account&&(!cash?.openingConfirmed||!cash?.openingOn||cash.archivedAt)&&<p role="alert">请先初始化关联现金账户，或在投资账户中更新资金来源。</p>}
   {plan&&<p>已有期次保持原计划，不会因编辑而重写。</p>}
  </aside></div><footer className="plan-modal-footer"><span>仅创建提醒，不会自动买入或扣款</span><Button htmlType="submit" theme="solid" disabled={!valid} loading={save.isPending}>{plan?'保存计划':'创建提醒计划'}</Button></footer></form>
 </Modal>;
}

function OccurrenceResult({item}:{item:InvestmentPlanOccurrence}){
 if(item.state==='SKIPPED')return <>已跳过{item.reason?' · '+item.reason:''}</>;
 if(item.tradeReversed)return <>原成交已撤销 · 交易 #{item.tradeId}<small>保留本期处理历史，不会再次自动扣款。</small></>;
 const current=item.currentTrade;
 return <>已记账 · 交易 #{item.tradeId}{current&&<small>现成交记录：{current.quantity} 股 × {money(current.price,item.currency)}，手续费 {money(current.fee,item.currency)} · {dateText(current.tradedOn)}</small>}</>;
}

function PlanConfirmation({request,occurrence,accounts,cashAccounts,onClose,onSaved}:Omit<Props,'manager'|'supportState'>&{occurrence:InvestmentPlanOccurrence;onClose:()=>void;onSaved:()=>Promise<void>}){
 const [key]=useState(newIdempotencyKey),[quantity,setQuantity]=useState(quantityText(occurrence.quantity)),[price,setPrice]=useState(''),[fee,setFee]=useState('0'),[tradedOn,setTradedOn]=useState(businessDate()),[ack,setAck]=useState(false);
 const fundsError=useFundsRefresh(),cash=cashAccounts.find(a=>a.id===occurrence.fundingAccountId),account=accounts.find(a=>a.id===occurrence.accountId);
 const total=tradeCash(quantity,price,fee),balance=cents(cash?.availableBalance),payment=cents(total);
 const positivePrice=unitPrice(price);
 const valid=ack&&Boolean(quantity.replace(/[0.]/g,'').length>0&&positivePrice!==null&&positivePrice>0n&&account&&account.fundingAccountId===occurrence.fundingAccountId&&cash?.currency===occurrence.currency&&cash.openingConfirmed&&cash.openingOn&&!cash.archivedAt&&payment!==null&&payment>0n&&balance!==null&&balance>=payment);
 const save=useMutation({mutationFn:()=>request(`/api/investment-plans/occurrences/${occurrence.id}/confirm`,{method:'POST',headers:{'Idempotency-Key':key},body:{quantity,price,fee,tradedOn}}),onError:fundsError,onSuccess:onSaved});
 return <Modal visible title="确认本期实际成交" width={850} footer={null} maskClosable={false} closeOnEsc={!save.isPending} onCancel={()=>{if(!save.isPending)onClose();}} className="plan-modal investment-dialog"><form onSubmit={e=>{e.preventDefault();if(valid)save.mutate();}}>
  <div className="plan-dialog-grid"><fieldset className="feature-form plan-settings" disabled={save.isPending}><FormError compact error={save.error}/><div className="plan-trade-title"><span>{occurrence.symbol} · {dateText(occurrence.dueOn)}</span><h3>{occurrence.securityName}</h3><p>{occurrence.quantity!=null?`计划 ${planTarget(occurrence)}；以实际成交为准。`:`旧预算 ${money(occurrence.amount,occurrence.currency)}；请填写实际成交股数。`}</p></div>
   <label>实际成交数量<input name="quantity" required inputMode="decimal" value={quantity} onChange={e=>setQuantity(e.target.value)}/></label>
   <label>实际成交单价<input name="price" required inputMode="decimal" value={price} onChange={e=>setPrice(e.target.value)}/></label>
   <label>手续费<input name="fee" required inputMode="decimal" value={fee} onChange={e=>setFee(e.target.value)}/></label>
   <label>成交日期<DateField name="tradedOn" required max={businessDate()} value={tradedOn} onChange={e=>setTradedOn(e.target.value)}/></label>
   <label className="plan-ack"><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/>我已在券商完成实际买入，以上是成交记录</label>
  </fieldset><aside className="plan-summary"><span className="plan-summary-label">本次记账</span><PaymentPreview compact account={cash} amount={total}/>{(!account||account.fundingAccountId!==occurrence.fundingAccountId)&&<p role="alert">投资账户已归档或资金关联已更改，请先核对账户设置；本期不会改用其他账户扣款。</p>}<p>只会登记这一期。其他未确认期次不会合并扣款。</p></aside></div>
  <footer className="plan-modal-footer"><span>实际扣款 {money(total,occurrence.currency)}</span><Button htmlType="submit" theme="solid" disabled={!valid} loading={save.isPending}>确认已成交并记账</Button></footer>
 </form></Modal>;
}
