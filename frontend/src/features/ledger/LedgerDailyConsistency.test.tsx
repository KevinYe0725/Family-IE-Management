import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {TransactionsPage} from './TransactionsPage';
import type {RequestFn} from '../common';

const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
const records=[
 {id:1,kind:'expense',amount:'1060.00',principalAmount:'1000.00',interestAmount:'60.00',sourceType:'LOAN_PAYMENT',categoryId:3,categoryName:'居家',occurredOn:'2026-09-10',merchant:'到期还款'},
 {id:2,kind:'expense',amount:'2000.00',principalAmount:'2000.00',interestAmount:'0.00',sourceType:'LOAN_PREPAYMENT',categoryId:3,categoryName:'居家',occurredOn:'2026-09-10',merchant:'额外还本'},
 {id:3,kind:'income',amount:'22000.00',sourceType:'MANUAL',categoryId:1,categoryName:'工资',occurredOn:'2026-09-10',merchant:'工资到账'},
 {id:4,kind:'expense',amount:'42.80',sourceType:'MANUAL',categoryId:4,categoryName:'外卖',occurredOn:'2026-09-09',merchant:'晚餐'}
].map(r=>({...r,currency:'CNY',accountId:1,accountName:'人民币账户',memberId:1,memberName:'成员',createdByUserId:1,createdByName:'用户'}));
function show(){
 const savedRows:Array<Omit<typeof records[number],'principalAmount'|'interestAmount'>&{principalAmount?:string;interestAmount?:string}>=[...records];
 const request=vi.fn(async(path:string,options?:{method?:string;body?:unknown})=>{
  if(path==='/api/transactions'&&options?.method==='POST'){
   const value={...records[2],...(options.body as Partial<typeof records[number]>),id:9};savedRows.push(value);return value;
  }
  const url=new URL(path,'http://test.local');const params=url.searchParams;
  if(url.pathname.startsWith('/api/transactions')){
   const rows=savedRows.filter(r=>(!params.get('kind')||r.kind===params.get('kind'))&&(!params.get('from')||r.occurredOn===params.get('from'))&&(!params.get('categoryId')||r.categoryId===Number(params.get('categoryId'))));
   if(url.pathname==='/api/transactions')return page(rows);
   const income=rows.filter(r=>r.kind==='income').reduce((s,r)=>s+Number(r.amount),0),expense=rows.filter(r=>r.kind==='expense').reduce((s,r)=>s+Number(r.amount),0);
   const groups=new Map<string,{date:string;kind:string;categoryId:number;amount:string;count:number}>();
   for(const r of rows){const key=r.occurredOn+':'+r.categoryId;const old=groups.get(key);groups.set(key,{date:r.occurredOn,kind:r.kind,categoryId:r.categoryId,amount:(Number(old?.amount??0)+Number(r.amount)).toFixed(2),count:(old?.count??0)+1});}
   return {currency:'CNY',income:income.toFixed(2),expense:expense.toFixed(2),balance:(income-expense).toFixed(2),transactionCount:rows.length,unconvertedCount:0,categories:[...new Set(rows.map(r=>r.categoryId))].map(id=>{const items=rows.filter(r=>r.categoryId===id);return {categoryId:id,name:items[0].categoryName,color:'#526dcd',kind:items[0].kind,amount:items.reduce((s,r)=>s+Number(r.amount),0).toFixed(2),count:items.length};}),daily:[...groups.values()]};
  }
  if(path==='/api/members')return [{id:1,name:'成员'}];
  if(path.startsWith('/api/accounts?'))return page([{id:1,name:'人民币账户',type:'CASH',currency:'CNY',openingConfirmed:true,openingOn:'2026-01-01',availableBalance:'50000.00'}]);
  if(path.startsWith('/api/categories?'))return page([{id:1,name:'工资',kind:'income',color:'#526dcd',parentId:null,level:1},{id:3,name:'居家',kind:'expense',color:'#398573',parentId:null,level:1}]);
  if(path==='/api/currencies')return {currencies:['CNY']};return page([]);
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><TransactionsPage request={request as RequestFn} role="OWNER" userId={1}/></QueryClientProvider>);
 return request;
}
it('opens in expense mode and uses the same direction for the chart and records',async()=>{
 const request=show();await screen.findByRole('button',{name:'筛选2026-09-10支出'});
 expect(screen.queryByRole('row',{name:/工资到账/})).not.toBeInTheDocument();
 const summary=screen.getByRole('region',{name:'支出汇总'});
 expect(summary).toHaveTextContent('¥3,102.80');expect(summary).not.toHaveTextContent('收入');
 expect(screen.queryByText('收支差额')).not.toBeInTheDocument();
 for(const [path] of request.mock.calls.filter(([p])=>p.startsWith('/api/transactions')))expect(new URL(path,'http://test.local').searchParams.get('kind')).toBe('expense');
});
it('preserves the month selected on the homepage',async()=>{
 window.history.replaceState({},'', '/workspace/transactions?month=2026-08');
 try {
  const request=show();await screen.findByRole('region',{name:'支出汇总'});
  expect(screen.getByRole('textbox',{name:'账期'})).toHaveValue('2026-08');
  expect(request.mock.calls.some(([path])=>path.startsWith('/api/transactions?month=2026-08'))).toBe(true);
 } finally {window.history.replaceState({},'', '/');}
});
it('reveals an income saved from expense view and defaults the next draft to the visible direction',async()=>{
 show();await screen.findByRole('button',{name:'筛选2026-09-10支出'});
 await userEvent.click(screen.getByRole('button',{name:'记一笔'}));
 let dialog=screen.getByRole('dialog',{name:'记一笔'});
 await userEvent.click(within(dialog).getByRole('radio',{name:'收入'}));
 await userEvent.type(within(dialog).getByLabelText('金额'),'123.45');
 await userEvent.selectOptions(within(dialog).getByLabelText('分类'),'1');
 await userEvent.type(within(dialog).getByLabelText('商家'),'新增模拟收入');
 await userEvent.click(within(dialog).getByRole('button',{name:'保存收支'}));
 expect(await screen.findByRole('row',{name:/新增模拟收入/})).toBeInTheDocument();
 expect(screen.getByRole('combobox',{name:'收支类型筛选'})).toHaveValue('income');
 await userEvent.click(screen.getByRole('button',{name:'记一笔'}));dialog=screen.getByRole('dialog',{name:'记一笔'});
 expect(within(dialog).getByRole('radio',{name:'收入'})).toBeChecked();
});
it('keeps income and expense drilldown separate and exposes an exact daily total',async()=>{
 const request=show();const day=await screen.findByRole('button',{name:'筛选2026-09-10支出'});
 expect(day).toHaveAccessibleDescription('2026-09-10 支出合计 ¥3,060.00');
 await userEvent.click(day);
 await waitFor(()=>expect(screen.getByRole('region',{name:'支出汇总'})).toHaveTextContent('¥3,060.00'));
 expect(screen.queryByRole('row',{name:/晚餐/})).not.toBeInTheDocument();expect(screen.queryByRole('row',{name:/工资到账/})).not.toBeInTheDocument();
 await userEvent.click(within(screen.getByRole('region',{name:'收支统计'})).getByRole('button',{name:'收入'}));
 expect(await screen.findByRole('region',{name:'收入汇总'})).toHaveTextContent('¥22,000.00');
 expect(screen.queryByRole('region',{name:'支出汇总'})).not.toBeInTheDocument();
 expect(await screen.findByRole('row',{name:/工资到账/})).toBeInTheDocument();
 expect(screen.queryByRole('row',{name:/额外还本/})).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'筛选2026-09-10收入'}));
 await waitFor(()=>expect(request.mock.calls.some(([p])=>p.startsWith('/api/transactions?')&&p.includes('from=2026-09-10')&&p.includes('kind=income'))).toBe(true));
 await userEvent.click(screen.getByRole('button',{name:'清除图表筛选'}));
 expect(screen.getByRole('combobox',{name:'收支类型筛选'})).toHaveValue('income');
});
