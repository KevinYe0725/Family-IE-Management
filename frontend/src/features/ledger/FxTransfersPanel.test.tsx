import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {FxTransfersPanel} from './FxTransfersPanel';
import type {Account} from '../../api/contracts';
import type {RequestFn} from '../common';
it('fills daily estimates only on request and never overwrites manual proceeds on refresh',async()=>{
 const accounts=[{id:1,name:'人民币卡',currency:'CNY',availableBalance:'9000.00',openingConfirmed:true,type:'BANK'}, {id:2,name:'美元卡',currency:'USD',availableBalance:'0.00',openingConfirmed:true,type:'BANK'}] as Account[];
 let rate='7',rejectRefresh=false;const request=(async(path:string,options?:any)=>{
  if(path.startsWith('/api/exchange-rates')){const day=new URL(path,'http://test').searchParams.get('asOf')!;if(options?.method==='POST'){if(rejectRefresh)throw new Error('参考汇率刷新失败');rate='8';}return {asOf:day,rows:[{currency:'CNY',cnyPerUnit:'1',effectiveOn:day,state:'READY',source:'IDENTITY',batchId:null},{currency:'USD',cnyPerUnit:rate,effectiveOn:day,state:'READY',source:'ECB',batchId:1}]};}
  return {items:[],page:0,size:20,totalElements:0,totalPages:0,hasNext:false};
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><FxTransfersPanel request={request} role="OWNER" accounts={accounts}/></QueryClientProvider>);
 const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'记录换汇'}));const form=within(screen.getByRole('dialog'));
 await user.selectOptions(form.getByLabelText('转出账户'),'1');await user.selectOptions(form.getByLabelText('转入账户币种'),'USD');await user.selectOptions(form.getByLabelText('转入账户'),'2');
 await user.type(form.getByLabelText('实际转出本金'),'7000');expect(form.getByLabelText('实际到账金额')).toHaveValue('');
 await user.click(await form.findByRole('button',{name:'使用日度参考汇率'}));expect(form.getByLabelText('实际到账金额')).toHaveValue('1000.00');
 await user.clear(form.getByLabelText('实际到账金额'));await user.type(form.getByLabelText('实际到账金额'),'123.45');
 rejectRefresh=true;await user.click(form.getByRole('button',{name:'刷新参考汇率'}));await form.findByText('参考汇率刷新失败');
 await user.clear(form.getByLabelText('实际转出本金'));await user.type(form.getByLabelText('实际转出本金'),'7000');
 expect(form.getByRole('button',{name:'使用日度参考汇率'})).toBeEnabled();expect(form.queryByText('参考汇率刷新失败')).not.toBeInTheDocument();
 rejectRefresh=false;
 await user.click(form.getByRole('button',{name:'刷新参考汇率'}));await waitFor(()=>expect(form.getByText(/0.125000/)).toBeInTheDocument());
 expect(form.getByLabelText('实际到账金额')).toHaveValue('123.45');
 await user.click(form.getByRole('button',{name:'使用日度参考汇率'}));expect(form.getByLabelText('实际到账金额')).toHaveValue('875.00');
 await user.clear(form.getByLabelText('实际转出本金'));expect(form.getByLabelText('实际到账金额')).toHaveValue('');
});
it('opens a selected bank exchange with its two currency child ids',async()=>{
 const accounts=[{id:21,name:'人民币',bankAccountId:10,bankAccountName:'汇丰 One',currency:'CNY',availableBalance:'100.00',openingConfirmed:true,type:'BANK'}, {id:22,name:'美元',bankAccountId:10,bankAccountName:'汇丰 One',currency:'USD',availableBalance:'0.00',openingConfirmed:true,type:'BANK'}] as Account[];
 const handled=vi.fn();const request=(async(path:string)=>path.includes('exchange-rates')?{rows:[]}:{items:[],page:0,size:20,totalElements:0,totalPages:0,hasNext:false}) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><FxTransfersPanel request={request} role="OWNER" accounts={accounts} initialBankId={10} onInitialHandled={handled}/></QueryClientProvider>);
 const dialog=within(await screen.findByRole('dialog'));expect(dialog.getByLabelText('转出账户')).toHaveValue('21');expect(dialog.getByLabelText('转入账户')).toHaveValue('22');expect(handled).toHaveBeenCalledOnce();
});
it('retains an uncertain exchange across unmounts and inactive query garbage collection',async()=>{
 const writes:unknown[]=[];
 const accounts=[{id:1,name:'人民币卡',currency:'CNY',availableBalance:'7010.00',openingConfirmed:true,type:'BANK'}, {id:2,name:'美元卡',currency:'USD',availableBalance:'0.00',openingConfirmed:true,type:'BANK'}] as Account[];
 const client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:1}}});
 const request:RequestFn=async<T,>(path:string,options?:Parameters<RequestFn>[1])=>{
  if(options?.method==='POST'){writes.push(structuredClone(options.body));if(writes.length===1)throw new TypeError('连接中断');return {id:1} as T;}
  return (path.includes('exchange-rates')?{rows:[]}:{items:[],page:0,size:20,totalElements:0,totalPages:0,hasNext:false}) as T;
 };
 const element=<QueryClientProvider client={client}><FxTransfersPanel request={request} role="OWNER" accounts={accounts}/></QueryClientProvider>;
 const view=render(element);const user=userEvent.setup();
 await user.click(screen.getByRole('button',{name:'记录换汇'}));
 let form=within(screen.getByRole('dialog'));
 await user.selectOptions(form.getByLabelText('转出账户'),'1');await user.selectOptions(form.getByLabelText('转入账户币种'),'USD');await user.selectOptions(form.getByLabelText('转入账户'),'2');
 await user.type(form.getByLabelText('实际转出本金'),'7000');await user.type(form.getByLabelText('实际到账金额'),'1000');
 await user.click(form.getByRole('button',{name:'确认记录换汇'}));await screen.findByText('连接中断');
 view.unmount();client.setQueryData(['inactive-gc-probe'],true);
 await waitFor(()=>expect(client.getQueryData(['inactive-gc-probe'])).toBeUndefined());
 render(element);
 await user.click(screen.getByRole('button',{name:'继续核对上次换汇'}));form=within(screen.getByRole('dialog'));
 expect(form.getByLabelText('实际转出本金')).toHaveValue('7000');
 expect(form.getByLabelText('实际转出本金')).toBeDisabled();
 await user.click(form.getByRole('button',{name:'核对本次换汇结果'}));
 await waitFor(()=>expect(writes).toHaveLength(2));expect(writes[1]).toEqual(writes[0]);
});
it('previews both currencies and retries an uncertain exchange with the identical request',async()=>{
 const writes:unknown[]=[];const accounts=[{id:1,name:'人民币卡',currency:'CNY',availableBalance:'7010.00',openingConfirmed:true,type:'BANK'}, {id:2,name:'美元卡',currency:'USD',availableBalance:'0.00',openingConfirmed:true,type:'BANK'}] as Account[];
 const request:RequestFn=async<T,>(path:string,options?:Parameters<RequestFn>[1])=>{
  if(options?.method==='POST'){writes.push(structuredClone(options.body));if(writes.length===1)throw new TypeError('连接中断');return {id:1} as T;}
  return (path.includes('exchange-rates')?{rows:[]}:{items:[],page:0,size:20,totalElements:0,totalPages:0,hasNext:false}) as T;
 };
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><FxTransfersPanel request={request} role="OWNER" accounts={accounts}/></QueryClientProvider>);
 const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'记录换汇'}));const form=within(screen.getByRole('dialog'));
 await user.selectOptions(form.getByLabelText('转出账户'),'1');await user.selectOptions(form.getByLabelText('转入账户币种'),'USD');await user.selectOptions(form.getByLabelText('转入账户'),'2');
 await user.type(form.getByLabelText('实际转出本金'),'7000');await user.type(form.getByLabelText('实际到账金额'),'1000');
 await user.clear(form.getByLabelText('手续费（CNY）'));await user.type(form.getByLabelText('手续费（CNY）'),'10');
 expect(form.getByText('预计余额 ¥0.00')).toBeInTheDocument();expect(form.getByText('预计余额 USD 1,000.00')).toBeInTheDocument();
 await user.click(form.getByRole('button',{name:'确认记录换汇'}));await screen.findByText('连接中断');
 expect(form.getByLabelText('实际转出本金')).toBeDisabled();await user.click(form.getByRole('button',{name:'核对本次换汇结果'}));
 await waitFor(()=>expect(writes).toHaveLength(2));expect(writes[1]).toEqual(writes[0]);
});
