import {act,render,screen,waitFor} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import {InvestmentsPage} from './InvestmentsPage';
import type {RequestFn} from '../common';

const page=(items:unknown[])=>({items,page:0,size:50,totalPages:items.length?1:0,totalElements:items.length,hasNext:false});
const account={id:3,name:'A股账户',brokerName:'券商',currency:'CNY',fundingAccountId:4,status:'ACTIVE',createdBy:7,archivedAt:null};
const cash={id:4,name:'人民币现金',currency:'CNY',type:'BANK',balance:'500.00',availableBalance:'500.00',openingConfirmed:true,openingOn:'2026-01-01',archivedAt:null};
const emptyPortfolio={positions:[],totals:{cost:'0.00',estimatedValue:'0.00',marketValue:'0.00',realizedProfit:'0.00',unrealizedProfit:'0.00',totalProfit:'0.00',unpricedPositions:0}};
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(done=>{resolve=done;});return {promise,resolve};}
function renderPage(request:RequestFn){
 window.history.replaceState({},'','/workspace/investments?tab=plans');
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}})}><InvestmentsPage request={request} role="OWNER"/></QueryClientProvider>);
}
function baseResponse<T>(path:string):T{
 if(path==='/api/investment-plans')return {plans:[],occurrences:[],pendingCount:0} as T;
 if(path==='/api/portfolio')return emptyPortfolio as T;
 if(path.startsWith('/api/investment-trades'))return page([]) as T;
 if(path==='/api/market-quotes')return [] as T;
 if(path==='/api/currencies')return {currencies:['CNY']} as T;
 throw new Error(`Unexpected ${path}`);
}
afterEach(()=>window.history.replaceState({},'','/'));

it('shows account support loading honestly and becomes usable after the read completes',async()=>{
 const gate=deferred<ReturnType<typeof page>>();
 const request=(async<T,>(path:string)=>{
  if(path.startsWith('/api/investment-accounts'))return gate.promise as Promise<T>;
  if(path.startsWith('/api/accounts'))return page([cash]) as T;
  return baseResponse<T>(path);
 }) as RequestFn;
 renderPage(request);
 expect(await screen.findByText('正在读取定投所需的投资账户和资金账户…')).toBeInTheDocument();
 const create=screen.getByRole('button',{name:'新建定投计划'});
 expect(create).toBeDisabled();
 expect(screen.queryByText(/请先.*创建.*投资账户/)).not.toBeInTheDocument();
 await act(async()=>{gate.resolve(page([account]));});
 await waitFor(()=>expect(create).toBeEnabled());
});

it('shows a recoverable cash-account failure instead of treating it as no account',async()=>{
 let cashReads=0;
 const request=(async<T,>(path:string)=>{
  if(path.startsWith('/api/investment-accounts'))return page([account]) as T;
  if(path.startsWith('/api/accounts')){cashReads+=1;if(cashReads===1)throw new Error('资金账户读取失败');return page([cash]) as T;}
  return baseResponse<T>(path);
 }) as RequestFn;
 renderPage(request);
 expect(await screen.findByRole('alert')).toHaveTextContent('资金账户读取失败');
 const create=screen.getByRole('button',{name:'新建定投计划'});
 expect(create).toBeDisabled();
 expect(screen.queryByText(/请先.*创建.*投资账户/)).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'重试定投账户数据'}));
 await waitFor(()=>expect(create).toBeEnabled());
 expect(cashReads).toBe(2);
});
