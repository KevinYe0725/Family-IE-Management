import {render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {TransactionsPage} from './TransactionsPage';
import type {RequestFn} from '../common';
it('shows a readonly loan receipt in all cash movements without classifying it as salary income',async()=>{
 const request=vi.fn(async(path:string)=>{
  if(path.startsWith('/api/cash-movements'))return {items:[{id:'55:1',journalId:55,sourceType:'LOAN_DISBURSEMENT',sourceId:8,effectiveOn:'2026-09-09',accountId:1,accountName:'收款银行卡',currency:'CNY',kind:'income',amount:'9800.00',internalTransfer:false,description:'消费贷'}],page:0,size:20,totalElements:1,totalPages:1,hasNext:false};
  if(path==='/api/members'||path==='/api/bank-accounts')return [];
  if(path.startsWith('/api/transactions/summary'))return {currency:'CNY',income:'0.00',expense:'0.00',balance:'0.00',transactionCount:0,unconvertedCount:0,categories:[],daily:[]};
  if(path==='/api/currencies')return {currencies:['CNY']};
  return {items:[],page:0,size:50,totalPages:0,totalElements:0,hasNext:false};
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={1}/></QueryClientProvider>);
 await userEvent.click(screen.getByRole('button',{name:'资金流水'}));
 const panel=await screen.findByRole('region',{name:'全部资金流水'});
 expect(await within(panel).findByText('贷款到账')).toBeInTheDocument();
 expect(within(panel).getByText('+¥9,800.00')).toBeInTheDocument();
 expect(within(panel).queryByRole('button',{name:'编辑'})).not.toBeInTheDocument();
 expect(within(panel).queryByRole('button',{name:'删除'})).not.toBeInTheDocument();
 await userEvent.click(within(panel).getByRole('button',{name:'查看入账轨迹'}));
 expect(await screen.findByRole('dialog',{name:'资金流水详情'})).toHaveClass('action-dialog');
});
