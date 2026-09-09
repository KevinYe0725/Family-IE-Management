import {render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AssetSaleDialog} from './AssetSaleDialog';
import {AssetDetailsDialog} from './AssetDetailsDialog';
import type {Account,Asset} from '../../api/contracts';
import type {RequestFn} from '../common';
import {ApiError} from '../../api/client';

const preview={assetId:7,assetName:'家庭车辆',disposedOn:'2026-09-09',route:'DIRECT',proceeds:'50000.00',fee:'0.00',bookValue:'60000.00',bookGain:'-10000.00',totalPrincipal:'20000.00',totalInterest:'100.00',totalRepayment:'20100.00',netSettlement:'29900.00',balances:[{accountId:1,accountName:'银行卡',currency:'CNY',before:'100.00',change:'29900.00',after:'30000.00'}],loans:[{loanId:2,name:'车贷',mode:'PAYOFF',principal:'20000.00',interest:'100.00',total:'20100.00',remainingPrincipal:'0.00'}],retainedLoans:[],canConfirm:true,blockers:[],planToken:'snapshot'};
function show(failOnce:boolean|number=false,blocked=false){
 let attempts=0;
 const request=vi.fn(async(path:string,options?:{method?:string;body?:unknown})=>{
  if(path.endsWith('/loans'))return {loans:[{loanId:2,name:'车贷',status:'ACTIVE',remainingPrincipal:'20000.00'}]};
  if(path.endsWith('/sale-preview'))return {...preview,canConfirm:!blocked,blockers:blocked?['还款账户余额不足']:[]};
  if(path.endsWith('/sale')&&options?.method==='POST'){if(failOnce&&attempts++===0)throw typeof failOnce==='number'?new ApiError('身份验证失败',{status:failOnce}):new Error('网络中断');return {saleId:9,assetId:7,preview,recordedAt:'2026-09-09'};}
  return [];
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><AssetSaleDialog asset={{id:7,name:'家庭车辆',currentValue:'60000.00'} as Asset} accounts={[{id:1,name:'银行卡',currency:'CNY',openingConfirmed:true,openingOn:'2026-09-01',availableBalance:'100.00'} as Account]} accountsReady request={request as RequestFn} onClose={()=>{}}/></QueryClientProvider>);
 return request;
}
async function quote(){
 await screen.findByLabelText('车贷处理方式');
 await userEvent.type(screen.getByLabelText('出售总价'),'50000');
 await userEvent.selectOptions(screen.getByLabelText('车贷处理方式'),'PAYOFF');
 await userEvent.selectOptions(screen.getByLabelText('款项如何结算'),'DIRECT');
 await userEvent.selectOptions(screen.getByLabelText('差额收付账户'),'1');
 await userEvent.click(screen.getByRole('button',{name:'预览结算'}));
}
it('requires a separate authoritative preview before recording sale and highlights real account changes',async()=>{
 const request=show();await quote();
 expect((await screen.findAllByText('¥29,900.00')).length).toBeGreaterThan(0);
 expect(screen.getByText('¥30,000.00')).toBeInTheDocument();
 expect(request.mock.calls.filter(([p])=>p.endsWith('/sale'))).toHaveLength(0);
 expect(request.mock.calls.find(([p])=>p.endsWith('/sale-preview'))?.[1]?.body).toEqual(expect.objectContaining({repayments:[{loanId:2,mode:'PAYOFF'}],route:'DIRECT',cashAccountId:1,repaymentAccountId:1}));
 await userEvent.click(screen.getByRole('button',{name:'确认记录出售'}));
 expect(await screen.findByText('出售已记录')).toBeInTheDocument();
});
it('preserves the exact draft, token and idempotency key when retrying uncertain submission',async()=>{
 const request=show(true);await quote();await screen.findByRole('button',{name:'确认记录出售'});
 await userEvent.click(screen.getByRole('button',{name:'确认记录出售'}));
 await screen.findByText(/结果尚未确认/);
 expect(screen.queryByRole('button',{name:'返回修改'})).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'重试确认'}));
 await screen.findByText('出售已记录');
 const calls=request.mock.calls.filter(([p])=>p.endsWith('/sale'));
 expect(calls).toHaveLength(2);expect(calls[1]).toEqual(calls[0]);
});
it('prevents confirmation when authoritative settlement has insufficient funds',async()=>{
 show(false,true);await quote();await waitFor(()=>expect(screen.getByRole('button',{name:'确认记录出售'})).toBeDisabled());
 expect(screen.getByText('还款账户余额不足')).toBeInTheDocument();
});
it.each([401,403])('retains the original confirmation and explains authentication recovery after HTTP %s',async status=>{
 const request=show(status);await quote();await screen.findByRole('button',{name:'确认记录出售'});
 await userEvent.click(screen.getByRole('button',{name:'确认记录出售'}));
 expect(await screen.findByText(/另一标签页/)).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'重试确认'}));
 await screen.findByText('出售已记录');
 const calls=request.mock.calls.filter(([p])=>p.endsWith('/sale'));
 expect(calls[1]).toEqual(calls[0]);
});
it('requires explicit retention of linked debt and never records a sale just by opening the dialog',async()=>{
 const request=show();await screen.findByLabelText('车贷处理方式');
 await userEvent.type(screen.getByLabelText('出售总价'),'0');
 await userEvent.click(screen.getByRole('button',{name:'预览结算'}));
 expect(request.mock.calls.some(([p])=>p.endsWith('/sale-preview'))).toBe(false);
 await userEvent.click(screen.getByRole('checkbox',{name:'我确认出售后仍需偿还以上保留的贷款'}));
 await userEvent.click(screen.getByRole('button',{name:'预览结算'}));
 await screen.findByRole('button',{name:'确认记录出售'});
 expect(request.mock.calls.find(([p])=>p.endsWith('/sale-preview'))?.[1]?.body).toEqual(expect.objectContaining({retainUnselectedLoans:true,repayments:[],cashAccountId:null}));
});
it('keeps extra principal distinct from total payment and strips hidden fields when changing repayment mode',async()=>{
 const request=show();await screen.findByLabelText('车贷处理方式');
 await userEvent.type(screen.getByLabelText('出售总价'),'50000');
 await userEvent.selectOptions(screen.getByLabelText('车贷处理方式'),'PAYOFF');
 await userEvent.type(screen.getByLabelText('实际结清利息（选填）'),'200');
 await userEvent.selectOptions(screen.getByLabelText('车贷处理方式'),'PARTIAL');
 await userEvent.type(screen.getByLabelText('额外偿还本金'),'5000');
 await userEvent.selectOptions(screen.getByLabelText('剩余还款安排'),'ADJUST_TERM');
 await userEvent.type(screen.getByLabelText('剩余期数'),'12');
 await userEvent.selectOptions(screen.getByLabelText('剩余还款安排'),'REDUCE_PAYMENT');
 await userEvent.selectOptions(screen.getByLabelText('收款账户'),'1');
 await userEvent.click(screen.getByRole('button',{name:'预览结算'}));
 await screen.findByRole('button',{name:'确认记录出售'});
 expect(request.mock.calls.find(([p])=>p.endsWith('/sale-preview'))?.[1]?.body).toEqual(expect.objectContaining({repayments:[{loanId:2,mode:'PARTIAL',additionalPrincipal:'5000',strategy:'REDUCE_PAYMENT'}]}));
});
it('shows the stored sale receipt from archived asset details without offering another sale or unlinking origin history',async()=>{
 const request:RequestFn=async<T,>(path:string)=>(path==='/api/assets/7/sale'?{saleId:9,assetId:7,preview,recordedAt:'2026-09-09'}:path==='/api/assets/7/loans'?{financedPrincipal:'0.00',referenceEquity:'0.00',loans:[{loanId:2,name:'车贷',status:'CLOSED',relation:'FINANCING',remainingPrincipal:'0.00',originPurchase:true}]}:{id:7,name:'家庭车辆',type:'VEHICLE',status:'ARCHIVED',disposedOn:'2026-09-09',currentValue:'0.00',purchaseValue:'60000.00'}) as T;
 render(<QueryClientProvider client={new QueryClient()}><AssetDetailsDialog assetId={7} role="OWNER" request={request} onClose={()=>{}}/></QueryClientProvider>);
 expect(await screen.findByRole('region',{name:'出售结算明细'})).toHaveTextContent('¥29,900.00');
 expect(screen.getByRole('link',{name:'车贷'})).toHaveAttribute('href','/workspace/loans?loanId=2');
 expect(screen.queryByRole('button',{name:'关联已有贷款'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'解除关联'})).not.toBeInTheDocument();
});
