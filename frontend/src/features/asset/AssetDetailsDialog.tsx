import {AssetSaleReceipt} from './AssetSaleDialog';
import type {SaleResult} from './asset-sale';
import {useState} from 'react';
import {useMutation,useQuery} from '@tanstack/react-query';
import Button from '@douyinfe/semi-ui/lib/es/button';
import {Info} from 'lucide-react';
import type {Asset,HouseholdRole,Loan,Page} from '../../api/contracts';
import {readAllPages} from '../../shared/pagination';
import {newIdempotencyKey} from '../../shared/runtime';
import {ActionDialog,ConfirmDialog,FormError,QueryState,isManager,money,type RequestFn} from '../common';
import './asset-financing.scss';

type Relation='FINANCING'|'COLLATERAL';
export interface AssetLoans {assetId:number;financedPrincipal:string;referenceEquity:string;loans:Array<{loanId:number;name:string;status:string;relation:Relation;remainingPrincipal:string;originPurchase?:boolean}>}
const blank=()=>({loanId:'',relation:'FINANCING' as Relation,expectedAssetId:null as number|null,expectedRelation:null as Relation|null,previousName:'',key:newIdempotencyKey()});
export function AssetDetailsDialog({request,assetId,role,onClose}:{request:RequestFn;assetId:number;role:HouseholdRole;onClose:()=>void}){
 const [unlinkTarget,setUnlinkTarget]=useState<{loanId:number;relation:Relation;key:string}|null>(null);
 const asset=useQuery({queryKey:['assets','detail',assetId],queryFn:()=>request<Asset>(`/api/assets/${assetId}`)});
 const sale=useQuery({queryKey:['assets','sale',assetId],queryFn:()=>request<SaleResult|null>(`/api/assets/${assetId}/sale`),enabled:!!asset.data?.disposedOn});
 const financing=useQuery({queryKey:['assets','financing',assetId],queryFn:()=>request<AssetLoans>(`/api/assets/${assetId}/loans`)});
 const [linking,setLinking]=useState(false),[draft,setDraft]=useState(blank);
 const candidates=useQuery({queryKey:['loans','linkable',assetId],queryFn:()=>readAllPages(page=>request<Page<Loan>>(`/api/loans?status=ACTIVE&page=${page}&size=50`,{responseType:'page'})),enabled:linking});
 const save=useMutation({mutationFn:()=>request<Loan>(`/api/loans/${draft.loanId}/asset-link`,{method:'PUT',headers:{'Idempotency-Key':draft.key},body:{assetId,relation:draft.relation,expectedAssetId:draft.expectedAssetId,expectedRelation:draft.expectedRelation}}),onSuccess:()=>{setLinking(false);setDraft(blank());}});
 const unlink=useMutation({mutationFn:()=>request<Loan>(`/api/loans/${unlinkTarget!.loanId}/asset-link`,{method:'PUT',headers:{'Idempotency-Key':unlinkTarget!.key},body:{assetId:null,relation:null,expectedAssetId:assetId,expectedRelation:unlinkTarget!.relation}}),onSuccess:()=>setUnlinkTarget(null)});
 const eligible=(candidates.data??[]).filter(loan=>!loan.purchasedAssetId&&(draft.relation==='COLLATERAL'||loan.type===(asset.data?.type==='PROPERTY'?'MORTGAGE':asset.data?.type==='VEHICLE'?'CAR':'OTHER')));
 return <><ActionDialog open obscured={unlinkTarget!==null} size="wide" title={`${asset.data?.name??'资产'} · 资产详情`} draft={linking?draft:undefined} sessionKey={linking?draft.key:`asset:${assetId}`} busy={save.isPending} onClose={onClose}>
  <QueryState loading={asset.isLoading} error={asset.error}>
   {linking?<form className="feature-form" onSubmit={e=>{e.preventDefault();save.mutate();}}><FormError error={save.error}/><label>关联用途<select value={draft.relation} onChange={e=>setDraft({...blank(),relation:e.target.value as Relation})}><option value="FINANCING">用于购置</option><option value="COLLATERAL">作为抵押</option></select></label><label>选择贷款<select required disabled={candidates.isLoading||!!candidates.error} value={draft.loanId} onChange={e=>{const loan=candidates.data?.find(row=>row.id===Number(e.target.value));setDraft({...draft,loanId:e.target.value,expectedAssetId:loan?.linkedAssetId??null,expectedRelation:loan?.linkedAssetId!=null?loan.assetRelation??'FINANCING':null,previousName:loan?.linkedAssetName??(loan?.linkedAssetId?`资产 #${loan.linkedAssetId}`:'')});}}><option value="">请选择</option>{eligible.map(loan=><option value={loan.id} key={loan.id}>{loan.name} · 剩余 {money(loan.currentPrincipal)}</option>)}</select></label><QueryState loading={candidates.isLoading} error={candidates.error}>{!candidates.isLoading&&!candidates.error&&!eligible.length?<p>没有可关联的贷款。</p>:null}</QueryState>{draft.expectedAssetId!=null&&draft.expectedAssetId!==assetId&&<p role="status">将替换与“{draft.previousName}”的当前关联，不改变原账务。</p>}<p className="asset-link-note">只建立关系，不重复放款或登记资产。</p><div className="card-actions"><Button onClick={()=>{setLinking(false);setDraft(blank());save.reset();}}>返回详情</Button><Button htmlType="submit" theme="solid" type="primary" disabled={!draft.loanId||!!candidates.error} loading={save.isPending}>保存关联</Button></div></form>:<>
    <QueryState loading={financing.isLoading} error={financing.error}>
     <div className="asset-finance-metrics"><div><span>当前价值</span><strong>{money(asset.data?.currentValue)}</strong></div><div><span>购置融资余额</span><strong>{money(financing.data?.financedPrincipal)}</strong></div><div><span title="仅减去用于购置的活跃贷款本金；抵押关联不重复扣减，也不作为现金余额。">参考净值 <Info size={13} aria-hidden="true"/></span><strong>{money(financing.data?.referenceEquity)}</strong></div></div>
     <div className="asset-linked-loans">{financing.data?.loans.map(loan=><article key={loan.loanId}><div><a href={`/workspace/loans?loanId=${loan.loanId}`}>{loan.name}</a><span>{loan.relation==='COLLATERAL'?'抵押关联':'购置融资'} · {loan.status==='ACTIVE'?'进行中':loan.status==='CLOSED'?'已结清':'已归档'}</span></div><strong>{money(loan.remainingPrincipal)}</strong>{isManager(role)&&loan.originPurchase===false&&<button type="button" className="text-action" onClick={()=>{unlink.reset();setUnlinkTarget({loanId:loan.loanId,relation:loan.relation,key:newIdempotencyKey()});}}>解除关联</button>}</article>)}{!financing.data?.loans.length&&<p>暂无关联贷款</p>}</div>
    </QueryState>
    {asset.data&&<dl className="ledger-detail-fields"><div><dt>购入价值</dt><dd>{money(asset.data.purchaseValue)}</dd></div>{asset.data.property&&<div><dt>房产信息</dt><dd>{asset.data.property.address} · {asset.data.property.areaSqm}㎡</dd></div>}{asset.data.vehicle&&<div><dt>车辆信息</dt><dd>{asset.data.vehicle.brandModel}</dd></div>}</dl>}
    {asset.data?.disposedOn&&<QueryState loading={sale.isLoading} error={sale.error}>{sale.data?<AssetSaleReceipt preview={sale.data.preview}/>:<p>历史处置所得 {money(asset.data.disposalProceeds)} · 处置损益 {money(asset.data.disposalBookGain)}</p>}</QueryState>}
    {isManager(role)&&asset.data?.status==='ACTIVE'&&!asset.data.disposedOn&&<Button onClick={()=>{save.reset();setDraft(blank());setLinking(true);}}>关联已有贷款</Button>}
   </>}
  </QueryState>
 </ActionDialog><ConfirmDialog open={unlinkTarget!==null} title="解除贷款关联？" detail={<><p>只解除当前关联，不冲销贷款、资产或现金记录。</p><FormError error={unlink.error}/></>} confirmLabel="解除关联" loading={unlink.isPending} onClose={()=>{if(!unlink.isPending)setUnlinkTarget(null);}} onConfirm={()=>unlinkTarget&&unlink.mutate()}/></>;
}
