import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {TransactionsPage} from './TransactionsPage';
import {AccountIcon} from './AccountIdentity';
import type {RequestFn} from '../common';
import {ActionDialog} from '../common';

const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
const summary={currency:'CNY',income:'500.00',expense:'100.00',balance:'400.00',transactionCount:60,unconvertedCount:0,categories:[{categoryId:3,name:'餐饮',color:'#d8664b',kind:'expense',amount:'100.00',count:60}],daily:[{date:'2026-09-09',kind:'expense',categoryId:3,amount:'100.00',count:60}]};
function show(accounts:unknown[]=[],banks:unknown[]=[],currencies=['CNY','USD']){
 const request=vi.fn(async(path:string)=>{
  if(path.startsWith('/api/transactions/summary'))return summary;
  if(path==='/api/bank-accounts')return banks;
  if(path.startsWith('/api/accounts?'))return page(accounts);
  if(path==='/api/members')return [];
  if(path==='/api/currencies')return {currencies};
  return page([]);
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={1}/></QueryClientProvider>);
 return request;
}
it('keeps account transfers and accounting history inside account management dialogs',async()=>{
 show();
 expect(screen.queryByRole('button',{name:'账户互转'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'账务历史'})).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'账户'}));
 const accounts=await screen.findByRole('dialog',{name:'账户管理'});
 expect(accounts).toHaveClass('action-dialog');
 await userEvent.click(within(accounts).getByRole('button',{name:'账务历史'}));
 expect(await screen.findByRole('dialog',{name:'账务历史'})).toHaveClass('action-dialog');
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
});
it('offers currency exchange as an explicit transfer mode and opens its own dialog',async()=>{
 show();
 await userEvent.click(screen.getByRole('button',{name:'账户'}));
 await userEvent.click(within(await screen.findByRole('dialog',{name:'账户管理'})).getByRole('button',{name:'账户互转'}));
 let dialog=screen.getByRole('dialog',{name:'账户互转'});
 expect(within(dialog).getByRole('button',{name:'同币种互转'})).toHaveAttribute('aria-pressed','true');
 await userEvent.click(within(dialog).getByRole('button',{name:'换汇互转'}));
 expect(within(dialog).getByRole('heading',{name:'换汇记录'})).toBeInTheDocument();
 expect(within(dialog).queryByRole('button',{name:'记录账户互转'})).not.toBeInTheDocument();
 await userEvent.click(within(dialog).getByRole('button',{name:'记录换汇'}));
 dialog=await screen.findByRole('dialog',{name:'记录换汇'});
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
 expect(within(dialog).getByLabelText('实际转出本金')).toBeInTheDocument();
 expect(within(dialog).getByLabelText('实际到账金额')).toBeInTheDocument();
 await userEvent.click(within(dialog).getByRole('button',{name:'关闭'}));
 dialog=screen.getByRole('dialog',{name:'账户互转'});
 await userEvent.click(within(dialog).getByRole('button',{name:'同币种互转'}));
 expect(within(dialog).getByRole('button',{name:'记录账户互转'})).toBeInTheDocument();
});
it('opens the bank shortcut in exchange mode with the selected currency balances',async()=>{
 const accounts=[{id:21,name:'人民币',bankAccountId:10,bankAccountName:'测试卡',type:'BANK',currency:'CNY',openingConfirmed:true,availableBalance:'100.00'},
  {id:22,name:'美元',bankAccountId:10,bankAccountName:'测试卡',type:'BANK',currency:'USD',openingConfirmed:true,availableBalance:'10.00'}];
 show(accounts,[{id:10,name:'测试卡',bankName:'测试银行',accounts}]);
 await userEvent.click(screen.getByRole('button',{name:'账户'}));
 await userEvent.click(await screen.findByRole('button',{name:'管理余额'}));
 await userEvent.click(screen.getByRole('button',{name:'卡内换汇'}));
 const dialog=within(await screen.findByRole('dialog',{name:'记录换汇'}));
 expect(dialog.getByLabelText('转出账户')).toHaveValue('21');
 expect(dialog.getByLabelText('转入账户')).toHaveValue('22');
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
});
it('does not enable FX writes when the server only supports one currency',async()=>{
 show([],[],['CNY']);
 await userEvent.click(screen.getByRole('button',{name:'账户'}));
 await userEvent.click(within(await screen.findByRole('dialog',{name:'账户管理'})).getByRole('button',{name:'账户互转'}));
 const dialog=within(screen.getByRole('dialog',{name:'账户互转'}));
 expect(dialog.getByRole('button',{name:'换汇互转'})).toBeDisabled();
 expect(dialog.getByRole('button',{name:'记录账户互转'})).toBeEnabled();
});
it('uses full filtered summary and filters records when selecting a category',async()=>{
 const request=show();
 expect(await screen.findByRole('img',{name:/支出分类/})).toBeInTheDocument();
 expect(screen.getByRole('group',{name:'每日分类收支'})).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'筛选餐饮'}));
 await waitFor(()=>expect(request.mock.calls.some(([path])=>path.startsWith('/api/transactions?')&&path.includes('categoryId=3'))).toBe(true));
});
it('uses local bank SVGs and a generic fallback instead of guessing an unknown bank',()=>{
 const {container,rerender}=render(<AccountIcon account={{type:'BANK',bankName:'汇丰银行 HSBC One'}}/>);
 expect(container.querySelector('img')).toHaveAttribute('src','/bank-logos/hsbc.svg');
 rerender(<AccountIcon account={{type:'BANK',bankName:'不存在的银行'}}/>);
 expect(container.querySelector('img')).not.toBeInTheDocument();
});

it('keeps a nested editor mounted while showing only the active dialog',()=>{
 render(<ActionDialog open title="账户管理" obscured onClose={()=>{}}><ActionDialog open title="编辑银行卡" onClose={()=>{}}>编辑内容</ActionDialog></ActionDialog>);
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
 expect(screen.getByRole('dialog',{name:'编辑银行卡'})).toBeInTheDocument();
});

it('requires confirmation before archiving a wallet and returns to the account dialog on cancel',async()=>{
 const request=show([{id:90,name:'零用钱包',type:'WALLET',currency:'CNY',openingConfirmed:true,openingBalance:'0.00',balance:'0.00',availableBalance:'0.00'}]);
 await userEvent.click(screen.getByRole('button',{name:'账户'}));
 await userEvent.click(await screen.findByRole('button',{name:'归档'}));
 expect(await screen.findByRole('dialog',{name:'归档账户？'})).toBeInTheDocument();
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
 expect(request.mock.calls.some(([path])=>path==='/api/accounts/90')).toBe(false);
 await userEvent.click(screen.getByRole('button',{name:'取消'}));
 expect(screen.getByRole('dialog',{name:'账户管理'})).toBeInTheDocument();
});
