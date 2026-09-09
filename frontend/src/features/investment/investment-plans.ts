import {useQuery} from '@tanstack/react-query';
import type {RequestFn} from '../common';
import type {InvestmentTrade,Security} from '../../api/contracts';
export type PlanFrequency='WEEKLY'|'BIWEEKLY'|'MONTHLY';
export interface InvestmentPlan {
 id:number;name:string;accountId:number;accountName:string;fundingAccountId:number;securityId:number;securityName:string;symbol:string;
 currency:string;quantity:string|null;amount:string|null;frequency:PlanFrequency;firstDueOn:string;nextDueOn:string|null;assignedUserId:number;state:'ACTIVE'|'PAUSED'|'ENDED';
 security?:Security|null;
}
export interface InvestmentPlanOccurrence {
 id:number;planId:number;planName:string;accountId:number;accountName:string;fundingAccountId:number;securityId:number;securityName:string;symbol:string;
 currency:string;quantity:string|null;amount:string|null;dueOn:string;state:'PENDING'|'CONFIRMED'|'SKIPPED';remindAt:string|null;tradeId:number|null;actualQuantity:string|null;actualAmount:string|null;reason:string|null;
 tradeReversed?:boolean;currentTrade?:InvestmentTrade|null;
}
export interface InvestmentPlansResult {plans:InvestmentPlan[];occurrences:InvestmentPlanOccurrence[];pendingCount?:number;hasMorePlans?:boolean;hasMoreOccurrences?:boolean}
export const frequencyLabel:Record<PlanFrequency,string>={WEEKLY:'每周',BIWEEKLY:'每两周',MONTHLY:'每月'};
export function useInvestmentPlans(request:RequestFn,planPage=0,occurrencePage=0){
 return useQuery({queryKey:['investment-plans',planPage,occurrencePage],queryFn:()=>request<InvestmentPlansResult>(`/api/investment-plans${planPage||occurrencePage?`?planPage=${planPage}&occurrencePage=${occurrencePage}&size=20`:''}`),refetchInterval:60000,refetchIntervalInBackground:false,retry:false});
}

export function quantityText(value?:string|null){return value==null?'':value.includes('.')?value.replace(/0+$/,'').replace(/\.$/,''):value;}
export function planTarget(plan:{quantity:string|null;amount:string|null;currency:string}){return plan.quantity!=null?quantityText(plan.quantity)+' 股':'旧金额计划';}
export function validPlanQuantity(raw:string){return /^[0-9]{1,15}(\.[0-9]{1,4})?$/.test(raw)&&BigInt(raw.replace('.',''))>0n;}
