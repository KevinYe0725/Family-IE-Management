import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {InvestmentsPage} from './InvestmentsPage';
import type {RequestFn} from '../common';
vi.mock('klinecharts',()=>({init:()=>null,dispose:()=>{}}));
it('creates a US investment through the unified picker and previews USD cash with six-place price',async()=>{
 const writes:Array<unknown>=[];
 const security={id:7,tsCode:'AAPL.NASDAQ.US',symbol:'AAPL',name:'Apple',market:'US',currency:'USD',exchange:'NASDAQ',timezone:'America/New_York',active:true,securityType:'STOCK'};
 const page=(items:unknown[])=>({items,page:0,size:50,totalPages:1,totalElements:items.length,hasNext:false});
 const request:RequestFn=async<T,>(path:string,options?:Parameters<RequestFn>[1])=>{
  if(path==='/api/securities/overseas/resolve')return security as T;
  if(path==='/api/investment-trades'&&options?.method==='POST'){writes.push(options.body);return {} as T;}
  if(path==='/api/currencies')return {currencies:['CNY','HKD','USD']} as T;
  if(path==='/api/investment-setup')return {completed:true} as T;
  if(path==='/api/portfolio')return {positions:[],totals:{cost:'0',marketValue:'0',totalProfit:'0',unpricedPositions:0}} as T;
  if(path==='/api/market-quotes')return [] as T;
  if(path.includes('/overseas-market/search'))return {items:[security],state:'READY',stale:false,hasNext:false} as T;
  if(path.includes('/overseas-market/candles'))return {symbol:'AAPL',source:'SINA',adjustment:'none',supported:true,bars:[]} as T;
  if(path.includes('catalog-status'))return {state:'READY',count:5000} as T;
  if(path.startsWith('/api/investment-accounts'))return page([{id:1,name:'美元证券',currency:'USD',fundingAccountId:11}]) as T;
  if(path.startsWith('/api/accounts'))return page([{id:11,name:'美元现金',type:'BANK',currency:'USD',openingConfirmed:true,openingOn:'2026-01-01',availableBalance:'10.00',archivedAt:null}]) as T;
  return page([]) as T;
 };
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><InvestmentsPage request={request} role="OWNER"/></QueryClientProvider>);
 const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'记一笔投资'}));const form=within(screen.getByRole('dialog',{name:'记一笔投资'}));
 expect(await form.findByRole('option',{name:'美元证券 · USD'})).toBeInTheDocument();
 await user.click(form.getByRole('button',{name:'美股'}));await waitFor(()=>expect(form.getByRole('combobox',{name:'证券'})).toHaveAttribute('aria-disabled','false'));
 await user.click(form.getByRole('combobox',{name:'证券'}));await user.click(await screen.findByRole('option',{name:/AAPL.*Apple/}));
 // This integration case verifies trade precision, not per-keystroke search/IME behavior.
 await user.click(form.getByLabelText('数量'));await user.paste('1000');
 await user.click(form.getByLabelText('成交单价'));await user.paste('0.001234');
 expect(form.getByText('预计余额 USD 8.77')).toBeInTheDocument();
 await user.click(form.getByRole('button',{name:'保存投资记录'}));
 await waitFor(()=>expect(writes).toHaveLength(1));expect(writes[0]).toMatchObject({accountId:1,securityId:7,quantity:'1000',price:'0.001234',type:'BUY'});
});
