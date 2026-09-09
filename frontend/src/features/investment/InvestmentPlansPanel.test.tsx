import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {InvestmentPlansPanel} from './InvestmentPlansPanel';
import type {RequestFn} from '../common';

vi.mock('./TradeStockPicker',()=>({TradeStockPicker:({onChange,disabled,value}:any)=><div><span data-testid="selected-security">{value?`${value.market} ${value.tsCode} ${value.exchange??''}`:'none'}</span><button type="button" disabled={disabled} onClick={()=>onChange({id:8,name:'阿里巴巴',tsCode:'BABA.US',market:'US',symbol:'BABA',currency:'USD'})}>选择阿里巴巴</button></div>,securityCurrency:(s:any)=>s?.currency??'CNY'}));
const planSecurity={id:8,name:'阿里巴巴',tsCode:'BABA.US',market:'US',symbol:'BABA',currency:'USD',exchange:'NASDAQ',timezone:'America/New_York',securityType:'STOCK',active:true};
const plan={id:1,name:'长期积累',accountId:3,accountName:'美股账户',fundingAccountId:4,securityId:8,securityName:'阿里巴巴',symbol:'BABA',currency:'USD',quantity:'2.0000',amount:null,frequency:'MONTHLY',firstDueOn:'2026-09-01',nextDueOn:'2026-10-01',assignedUserId:7,state:'ACTIVE',security:planSecurity};
const occurrence={...plan,id:11,planId:1,planName:'长期积累',dueOn:'2026-09-01',state:'PENDING',remindAt:null,tradeId:null,actualAmount:null,reason:null};
const cash={id:4,name:'美元现金',currency:'USD',type:'BANK',balance:'500.00',availableBalance:'500.00',openingConfirmed:true,openingOn:'2026-01-01',archivedAt:null};
function setup(options:{pending?:boolean;manager?:boolean;fail?:boolean;reversed?:boolean;plan?:any;accounts?:any[];cashAccounts?:any[]}={}){
 const calls:Array<{path:string;options:any}>=[];
 const shownPlan=options.plan??plan;
 const request=(async(path:string,opts:any)=>{
  calls.push({path,options:opts});
  if(opts?.method){if(options.fail)throw new Error('余额不足，未记账');return {};}
  if(path==='/api/investment-plans')return {plans:[shownPlan],occurrences:options.pending===false?[]:[options.reversed?{...occurrence,state:'CONFIRMED',tradeId:44,actualAmount:'100.00',tradeReversed:true}:occurrence]};
  if(path.startsWith('/api/family/memberships'))return {items:[{id:1,userId:7,displayName:'叶凯文',status:'ACTIVE'}],page:0,size:50,totalElements:1,totalPages:1,hasNext:false};
  throw new Error('Unexpected '+path);
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><InvestmentPlansPanel request={request} manager={options.manager??true} accounts={options.accounts??[{id:3,name:'美股账户',brokerName:'券商',currency:'USD',fundingAccountId:4,status:'ACTIVE',createdBy:7,archivedAt:null}]} cashAccounts={options.cashAccounts??[cash as any]}/></QueryClientProvider>);
 return {calls,user:userEvent.setup()};
}
it('opening a pending occurrence never posts and requires actual execution acknowledgement',async()=>{
 const {calls,user}=setup();
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByLabelText('实际成交单价')).toHaveValue('');
 expect(within(dialog).getByLabelText('实际成交数量')).toHaveValue('2');
 expect(within(dialog).getByRole('button',{name:'确认已成交并记账'})).toBeDisabled();
 expect(calls.filter(c=>c.options?.method)).toHaveLength(0);
 await user.clear(within(dialog).getByLabelText('实际成交数量'));
 await user.type(within(dialog).getByLabelText('实际成交数量'),'2');
 await user.type(within(dialog).getByLabelText('实际成交单价'),'50');
 await user.click(within(dialog).getByLabelText('我已在券商完成实际买入，以上是成交记录'));
 await user.click(within(dialog).getByRole('button',{name:'确认已成交并记账'}));
 await waitFor(()=>expect(calls.some(c=>c.path==='/api/investment-plans/occurrences/11/confirm')).toBe(true));
 expect(calls.find(c=>c.path.endsWith('/confirm'))!.options.body).toMatchObject({quantity:'2',price:'50',fee:'0'});
});
it('shows a failure without dismissing the confirmation or pretending it posted',async()=>{
 const {user}=setup({fail:true});
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 await user.clear(within(dialog).getByLabelText('实际成交数量'));
 await user.type(within(dialog).getByLabelText('实际成交数量'),'2');
 await user.type(within(dialog).getByLabelText('实际成交单价'),'50');
 await user.click(within(dialog).getByLabelText('我已在券商完成实际买入，以上是成交记录'));
 await user.click(within(dialog).getByRole('button',{name:'确认已成交并记账'}));
 expect(await screen.findByText('余额不足，未记账')).toBeInTheDocument();
 expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('creates a quantity and cycle plan only after showing its linked cash account',async()=>{
 const {calls,user}=setup({pending:false});
 await user.click(screen.getByRole('button',{name:'新建定投计划'}));
 const dialog=screen.getByRole('dialog');
 await user.click(within(dialog).getByRole('button',{name:'选择阿里巴巴'}));
 await user.type(within(dialog).getByLabelText('每期计划股数'),'100');
 await user.selectOptions(within(dialog).getByLabelText('投资账户'),'3');
 await user.selectOptions(within(dialog).getByLabelText('提醒负责人'),'7');
 expect(within(dialog).getByText('美元现金')).toBeInTheDocument();
 expect(within(dialog).getByText('仅创建提醒，不会自动买入或扣款')).toBeInTheDocument();
 await user.click(within(dialog).getByRole('button',{name:'创建提醒计划'}));
 await waitFor(()=>expect(calls.some(c=>c.path==='/api/investment-plans'&&c.options?.method==='POST')).toBe(true));
 expect(calls.find(c=>c.options?.method==='POST')!.options.body).toMatchObject({accountId:3,securityId:8,quantity:'100',frequency:'MONTHLY',assignedUserId:7});
});
it('requires shares when editing a legacy amount plan without converting money to shares',async()=>{
 const {user,calls}=setup({pending:false,plan:{...plan,quantity:null,amount:'1000.00',state:'PAUSED'}});
 await user.click(await screen.findByRole('button',{name:'编辑计划'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByLabelText('每期计划股数')).toHaveValue('');
 await user.type(within(dialog).getByLabelText('每期计划股数'),'10');
 await user.click(within(dialog).getByRole('button',{name:'保存计划'}));
 await waitFor(()=>expect(calls.some(c=>c.options?.method==='PATCH')).toBe(true));
 const body=calls.find(c=>c.options?.method==='PATCH')!.options.body;
 expect(body.quantity).toBe('10');
 expect(body).not.toHaveProperty('amount');
});
it('does not expose accounting or lifecycle mutations to read-only members',async()=>{
 setup({manager:false});
 expect(await screen.findByText('长期积累',{selector:'h3'})).toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'新建定投计划'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'确认已成交'})).not.toBeInTheDocument();
});
it('keeps the date popup within the plan dialog and closes it before the dialog',async()=>{
 const {user}=setup();
 await user.click(await screen.findByRole('button',{name:'确认已成交'}));
 const dialog=screen.getByRole('dialog');
 await user.click(within(dialog).getByLabelText('成交日期'));
 expect((await within(dialog).findAllByRole('gridcell')).length).toBeGreaterThan(0);
 await user.keyboard('{Escape}');
 expect(within(dialog).queryAllByRole('gridcell')).toHaveLength(0);
 expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('shows reversed trades honestly without offering to confirm the same occurrence again',async()=>{
 setup({reversed:true});
 expect(await screen.findByText(/原成交已撤销/)).toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'确认已成交'})).not.toBeInTheDocument();
});
it('skips only the selected occurrence without sending a trade confirmation',async()=>{
 const {user,calls}=setup();
 await user.click(await screen.findByRole('button',{name:'阿里巴巴本期操作'}));
 await user.click(await screen.findByRole('menuitem',{name:'跳过本期'}));
 const dialog=screen.getByRole('dialog');
 await user.type(within(dialog).getByLabelText('原因（可选）'),'本期暂缓');
 await user.click(within(dialog).getByRole('button',{name:'确认跳过'}));
 await waitFor(()=>expect(calls.some(c=>c.path.endsWith('/11/skip'))).toBe(true));
 expect(calls.filter(c=>c.options?.method).map(c=>c.path)).toEqual(['/api/investment-plans/occurrences/11/skip']);
});

it.each([
 {market:'SZ',tsCode:'000001.SZ',symbol:'000001',exchange:'SZSE',name:'平安银行'},
 {market:'BJ',tsCode:'430047.BJ',symbol:'430047',exchange:'BSE',name:'诺思兰德'}
])('edits a $market plan with its backend security identity intact',async identity=>{
 const security={...planSecurity,...identity,id:18,currency:'CNY',timezone:'Asia/Shanghai'};
 const domesticPlan={...plan,securityId:18,securityName:identity.name,symbol:identity.symbol,currency:'CNY',security};
 const domesticAccount={id:3,name:'A股账户',brokerName:'券商',currency:'CNY',fundingAccountId:4,status:'ACTIVE',createdBy:7,archivedAt:null};
 const domesticCash={...cash,currency:'CNY',name:'人民币现金'};
 const {user}=setup({pending:false,plan:domesticPlan,accounts:[domesticAccount],cashAccounts:[domesticCash]});
 await user.click(await screen.findByRole('button',{name:'编辑计划'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByTestId('selected-security')).toHaveTextContent(`${identity.market} ${identity.tsCode} ${identity.exchange}`);
 expect(within(dialog).getByRole('button',{name:'保存计划'})).toBeEnabled();
});

it('blocks editing when the backend plan omits its security identity',async()=>{
 const {user}=setup({pending:false,plan:{...plan,security:undefined}});
 await user.click(await screen.findByRole('button',{name:'编辑计划'}));
 const dialog=screen.getByRole('dialog');
 expect(within(dialog).getByRole('alert')).toHaveTextContent('证券身份');
 expect(within(dialog).getByRole('button',{name:'保存计划'})).toBeDisabled();
});
