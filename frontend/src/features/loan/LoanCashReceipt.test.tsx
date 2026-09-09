import {render,screen,within} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import {LoansPage,loanCreatePayload,type LoanDraft} from './LoansPage';
import type {RequestFn} from '../common';

it('separates receipt from principal and omits receipt for opening loans',()=>{
 const draft={name:'消费贷',type:'OTHER',principal:'10000.00',disbursementAmount:'9800.00',fundingMode:'DISBURSEMENT',annualRate:'3.6',termMonths:'12',repaymentMethod:'EQUAL_PAYMENT',startOn:'2026-09-09',accountingOn:'2026-09-09',disbursementAccountId:'1',paymentAccountId:'1',paymentCategoryId:'2',assignedUserId:'1',memberId:'',linkedAssetId:'',customSchedule:[]} as LoanDraft;
 expect(loanCreatePayload(draft)).toMatchObject({principal:'10000.00',disbursementAmount:'9800.00'});
 expect(loanCreatePayload({...draft,fundingMode:'OPENING'}).disbursementAmount).toBeUndefined();
});
it('shows the withheld fee before saving and clears receipt when funding mode changes',async()=>{
 const request=(async(path:string)=>path==='/api/members'?[]:{items:[],page:0,size:50,totalPages:0,totalElements:0,hasNext:false}) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER"/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('button',{name:'新建贷款'}));
 await userEvent.selectOptions(screen.getByLabelText('入账方式'),'DISBURSEMENT');
 await userEvent.type(screen.getByLabelText('实际放款本金'),'10000.00');
 const receipt=screen.getByLabelText('实际到账金额');
 expect(receipt).toHaveValue('10000.00');
 await userEvent.clear(receipt);await userEvent.type(receipt,'9800.00');
 const preview=screen.getByRole('region',{name:'放款核对'});
 expect(within(preview).getByText('¥200.00')).toBeInTheDocument();
 expect(within(preview).getByText('预扣费用')).toBeInTheDocument();
 await userEvent.selectOptions(screen.getByLabelText('入账方式'),'OPENING');
 expect(screen.queryByLabelText('实际到账金额')).not.toBeInTheDocument();
});

it.each(['12000.00','9000.00'])('keeps a full-receipt correction preview aligned with changed principal %s',async principal=>{
 const loan={id:4,name:'全额到账贷款',type:'OTHER',principal:'10000.00',disbursementAmount:'10000.00',withheldFee:'0.00',currentPrincipal:'10000.00',fundingMode:'DISBURSEMENT',accountingInitialized:true,status:'ACTIVE',lastPaymentOn:null,accountingOn:'2026-01-01',startOn:'2026-01-01',annualRate:'0.03',termMonths:12,repaymentMethod:'EQUAL_PAYMENT',disbursementAccountId:1,paymentAccountId:1,paymentCategoryId:2,assignedUserId:1};
 const request=(async(path:string)=>{
  if(path==='/api/loans/4')return loan;
  if(path.startsWith('/api/loans?'))return {items:[loan],page:0,size:20,totalElements:1,totalPages:1,hasNext:false};
  if(path==='/api/members')return [];
  return {items:[],page:0,size:50,totalElements:0,totalPages:0,hasNext:false};
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LoansPage request={request} role="OWNER" userId={1}/></QueryClientProvider>);
 await userEvent.click(await screen.findByRole('button',{name:'查看计划'}));
 await userEvent.click(await screen.findByRole('button',{name:'更正未付款合同'}));
 const input=screen.getByLabelText('实际放款本金');
 await userEvent.clear(input);await userEvent.type(input,principal);
 expect(screen.getByLabelText('实际到账金额')).toHaveValue(principal);
 expect(within(screen.getByRole('region',{name:'放款核对'})).getByText('¥0.00')).toBeInTheDocument();
});
