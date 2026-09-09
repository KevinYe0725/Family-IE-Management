import {render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {LoansPage,loanCreatePayload,type LoanDraft} from './LoansPage';
import type {RequestFn} from '../common';
it('sends full purchase price and own cash separately from the loan',()=>{
 const draft={name:'车贷',type:'CAR',fundingMode:'FINANCED_PURCHASE',createPurchasedAsset:true,principal:'150000.00',purchaseValue:'200000.00',ownContributionAccountId:'6',assetRelation:'FINANCING',annualRate:'3',termMonths:'36',startOn:'2026-09-09',repaymentMethod:'EQUAL_PAYMENT',paymentAccountId:'6',paymentCategoryId:'3',assignedUserId:'1',linkedAssetId:'PURCHASED',memberId:'1',customSchedule:[]} as LoanDraft;
 expect(loanCreatePayload(draft)).toMatchObject({principal:'150000.00',purchaseValue:'200000.00',ownContributionAccountId:6,assetRelation:'FINANCING'});
 expect(loanCreatePayload({...draft,fundingMode:'OPENING',createPurchasedAsset:false,linkedAssetId:''}).purchaseValue).toBeUndefined();
});
it('previews own contribution rather than calling loan principal the whole asset value',async()=>{
 const request=(async(path:string)=>path==='/api/members'?[]:{items:[],page:0,size:50,totalElements:0,totalPages:0,hasNext:false}) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER"/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('button',{name:'新建贷款'}));
 await userEvent.selectOptions(screen.getByLabelText('入账方式'),'FINANCED_PURCHASE');
 await userEvent.type(screen.getByLabelText('贷款购买本金'),'150000');
 await userEvent.type(screen.getByLabelText('完整购置金额'),'200000');
 expect(within(screen.getByRole('region',{name:'购置资金核对'})).getByText('¥50,000.00')).toBeInTheDocument();
 expect(screen.getByLabelText('首付款账户')).toBeRequired();
});

it('opens the referenced loan directly from an asset link',async()=>{
 const old=window.location.href;window.history.replaceState({},'','/workspace/loans?loanId=5');
 const loan={id:5,name:'关联贷款',type:'CAR',status:'CLOSED',currentPrincipal:'0.00',principal:'10000.00',annualRate:'0.03',termMonths:12,repaymentMethod:'EQUAL_PAYMENT',paymentAccountId:1,paymentCategoryId:2,accountingInitialized:true};
 const request=(async(path:string)=>path==='/api/loans/5'?loan:path==='/api/members'?[]:{items:[],page:0,size:50,totalElements:0,totalPages:0,hasNext:false}) as RequestFn;
 try{
  render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="MEMBER"/></QueryClientProvider>);
  expect(await screen.findByRole('dialog',{name:'关联贷款 · 还款计划'})).toHaveClass('action-dialog');
 }finally{window.history.replaceState({},'',old);}
});

it('does not preview another cash deduction when correcting an existing financed purchase',async()=>{
 const loan={id:5,name:'购置合同',type:'CAR',status:'ACTIVE',principal:'150000.00',currentPrincipal:'150000.00',purchaseValue:'200000.00',ownContribution:'50000.00',ownContributionAccountId:6,fundingMode:'FINANCED_PURCHASE',purchasedAssetId:9,linkedAssetId:9,assetRelation:'FINANCING',accountingInitialized:true,annualRate:'0.03',termMonths:36,repaymentMethod:'EQUAL_PAYMENT',paymentAccountId:6,paymentCategoryId:2,startOn:'2026-01-01',accountingOn:'2026-01-01',lastPaymentOn:null};
 const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
 const request=(async(path:string)=>{
  if(path==='/api/loans/5')return loan;
  if(path.startsWith('/api/loans?'))return page([loan]);
  if(path.startsWith('/api/accounts'))return page([{id:6,name:'首付款卡',type:'BANK',currency:'CNY',openingConfirmed:true,balance:'10000.00',availableBalance:'10000.00'}]);
  return path==='/api/members'?[]:page([]);
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER"/></QueryClientProvider>);
 await userEvent.click(await screen.findByRole('button',{name:'查看计划'}));
 await userEvent.click(await screen.findByRole('button',{name:'更正未付款合同'}));
 expect(screen.getByLabelText('完整购置金额')).toHaveValue('200000.00');
 expect(screen.queryByRole('region',{name:'账务金额预览'})).not.toBeInTheDocument();
});
