import {render,screen} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {HomePortfolio} from './HomePortfolio';
import type {RequestFn} from '../common';
it('uses live valuation on the homepage while retaining recorded quantity and cost',async()=>{
 const p={accountId:1,accountName:'证券账户',securityId:1,tsCode:'000001.SZ',name:'平安银行',quantity:10,cost:'100.00',realizedProfit:'0.00',price:'10.00',marketValue:'100.00',unrealizedProfit:'0.00',currency:'CNY',source:'BAOSTOCK'};
 const request:RequestFn=async<T,>(path:string)=>(path==='/api/portfolio/live'?{portfolio:{positions:[{...p,price:'12.00',marketValue:'120.00',unrealizedProfit:'20.00',source:'TENCENT'}],totals:{marketValue:'120.00',unrealizedProfit:'20.00'}},quotes:[],nextRefreshSeconds:60}:{positions:[p],totals:{marketValue:'100.00',unrealizedProfit:'0.00'}}) as T;
 render(<QueryClientProvider client={new QueryClient()}><HomePortfolio request={request} stale={false}/></QueryClientProvider>);
 expect(await screen.findByText('¥12.00')).toBeInTheDocument();
 expect(screen.getByText('¥120.00')).toBeInTheDocument();
});
