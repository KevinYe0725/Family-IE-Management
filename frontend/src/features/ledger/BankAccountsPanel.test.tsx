import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {BankAccountsPanel} from './BankAccountsPanel';
import type {RequestFn} from '../common';
import {BankAccountEditor} from './BankAccountEditor';
const bank={id:9,name:'汇丰 One',bankName:'汇丰',cardLastFour:'1234',archivedAt:null,accounts:[{id:1,name:'人民币',currency:'CNY',bankAccountId:9,openingConfirmed:true,availableBalance:'100.00',archivedAt:null},{id:2,name:'美元',currency:'USD',bankAccountId:9,openingConfirmed:true,availableBalance:'50.00',archivedAt:null}]};
const wrap=(node:React.ReactNode)=><QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}>{node}</QueryClientProvider>;
it('shows one bank card with separate currency balances and uses parent id for history',async()=>{
 const history=vi.fn();render(wrap(<BankAccountsPanel request={(async()=>[bank]) as RequestFn} manager currencies={['CNY','USD']} onOpening={()=>{}} onExchange={()=>{}} onHistory={history}/>));
 expect(await screen.findAllByRole('heading',{name:'汇丰 One'})).toHaveLength(1);
 expect(screen.getByText('USD 50.00')).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'查看流水'}));expect(history).toHaveBeenCalledWith(9);
});
it('creates both currency balances only after explicit opening confirmation',async()=>{
 const request=vi.fn(async()=>bank);render(wrap(<BankAccountEditor request={request as RequestFn} currencies={['CNY','USD']} onClose={()=>{}} onSaved={()=>{}}/>));
 const user=userEvent.setup();await user.type(screen.getByLabelText('账户名称'),'汇丰 One');
 await user.type(screen.getByLabelText('CNY 期初余额'),'100');await user.click(screen.getByRole('button',{name:'添加币种'}));
 await user.type(screen.getByLabelText('USD 期初余额'),'50');
 expect(screen.getByRole('button',{name:'保存余额'})).toBeDisabled();expect(request).not.toHaveBeenCalled();
 await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'保存余额'}));
 await waitFor(()=>expect(request).toHaveBeenCalledWith('/api/bank-accounts',expect.objectContaining({body:expect.objectContaining({balances:[expect.objectContaining({currency:'CNY',openingBalance:'100'}),expect.objectContaining({currency:'USD',openingBalance:'50'})]})})));
});
it('recovers a failed bank query through its retry action',async()=>{
 const request=vi.fn().mockRejectedValueOnce(new Error('读取失败')).mockResolvedValue([bank]);
 render(wrap(<BankAccountsPanel request={request} manager currencies={['CNY','USD']} onOpening={()=>{}} onExchange={()=>{}} onHistory={()=>{}}/>));
 await userEvent.click(await screen.findByRole('button',{name:'重试银行卡数据'}));
 expect(await screen.findByRole('heading',{name:'汇丰 One'})).toBeInTheDocument();
});
it('passes the selected bank into currency exchange and hides it for single-currency deployments',async()=>{
 const exchange=vi.fn();const view=render(wrap(<BankAccountsPanel request={(async()=>[bank]) as RequestFn} manager currencies={['CNY','USD']} onOpening={()=>{}} onExchange={exchange} onHistory={()=>{}}/>));
 await userEvent.click(await screen.findByRole('button',{name:'管理余额'}));await userEvent.click(screen.getByRole('button',{name:'卡内换汇'}));expect(exchange).toHaveBeenCalledWith(9);view.unmount();
 render(wrap(<BankAccountsPanel request={(async()=>[bank]) as RequestFn} manager currencies={['CNY']} onOpening={()=>{}} onExchange={exchange} onHistory={()=>{}}/>));
 await userEvent.click(await screen.findByRole('button',{name:'管理余额'}));expect(screen.queryByRole('button',{name:'卡内换汇'})).not.toBeInTheDocument();
});
