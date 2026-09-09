import {render,screen,within} from '@testing-library/react';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {DashboardPage} from './DashboardPage';
import type {RequestFn} from '../common';
import {ApiError} from '../../api/client';
function show(unknown=false,legacyAsset=false){
 const request=(async(path:string)=>{
  if(path==='/api/cash-position')return {asOf:'2026-09-09',currency:'CNY',availableCash:unknown?null:'10000.00',knownAvailableCash:'10000.00',uninitializedCount:0,unconverted:unknown?[{accountId:3,currency:'USD',nativeAmount:'100.00'}]:[]};
  if(path==='/api/net-worth'){if(legacyAsset)throw new ApiError('资产期初待确认',{status:409,code:'ACCOUNTING_NOT_INITIALIZED'});return {netWorth:'7000.00',asset:'12000.00',liability:'5000.00',investment:{},history:[],allocation:[]};}
  if(path.startsWith('/api/dashboard'))return {summary:{income:'0.00',expense:'0.00',balance:'0.00'},daily:[]};
  if(path==='/api/portfolio')return {totals:{},positions:[]};
  return {overdueInstallments:0};
 }) as RequestFn;
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><DashboardPage request={request} role="OWNER"/></QueryClientProvider>);
}
it('shows available cash independently of net assets without subtracting debt twice',async()=>{
 show();
 expect(await screen.findByText('¥10,000.00')).toBeInTheDocument();
 expect(screen.getByText('¥7,000.00')).toBeInTheDocument();
 expect(screen.getByRole('region',{name:'账内可用现金'})).toBeInTheDocument();
});
it('does not present a known subtotal as complete cash when FX is missing',async()=>{
 show(true);
 const region=await screen.findByRole('region',{name:'账内可用现金'});
 expect(await within(region).findByText('待补齐')).toBeInTheDocument();
 expect(within(region).getByRole('link',{name:'补充汇率'})).toHaveAttribute('href','/workspace/investments?tab=rates');
});

it('keeps known cash visible when only the noncash asset history needs initialization',async()=>{
 show(false,true);
 expect(await screen.findByRole('link',{name:'去初始化账户'})).toBeInTheDocument();
 expect(await screen.findByText('¥10,000.00')).toBeInTheDocument();
 expect(screen.getAllByRole('alert')).toHaveLength(1);
});
