import {render,screen,within,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AssetsPage} from './AssetsPage';
import type {RequestFn} from '../common';
const asset={id:7,name:'家庭车辆',type:'VEHICLE',status:'ACTIVE',currentValue:'200000.00',purchaseValue:'200000.00',accountingMode:'PURCHASE',accountingOn:'2026-09-01',vehicle:{brandModel:'家用汽车'}};
const loan={id:2,name:'原贷款',type:'CAR',status:'ACTIVE',currentPrincipal:'20000.00',linkedAssetId:99,linkedAssetName:'原资产',assetRelation:'COLLATERAL'};
const page=(items:unknown[])=>({items,page:0,size:50,totalElements:items.length,totalPages:items.length?1:0,hasNext:false});
function show(role:'OWNER'|'MEMBER'='OWNER'){
 const request=vi.fn(async(path:string,options?:{method?:string;body?:unknown})=>{
  if(options?.method)return {...loan,id:Number(path.split('/')[3])};
  if(path==='/api/assets/7/loans')return {assetId:7,financedPrincipal:'150000.00',referenceEquity:'50000.00',loans:[{loanId:3,name:'购置融资',status:'ACTIVE',relation:'FINANCING',remainingPrincipal:'150000.00',originPurchase:true},{loanId:4,name:'抵押借款',status:'ACTIVE',relation:'COLLATERAL',remainingPrincipal:'30000.00',originPurchase:false}]};
  if(path==='/api/assets/7')return asset;
  if(path.startsWith('/api/assets?'))return page([asset]);
  if(path.startsWith('/api/loans?'))return page([loan]);
  if(path==='/api/net-worth')return {asset:'200000.00'};
  if(path==='/api/members')return [];
  return page([]);
 });
 render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><AssetsPage request={request as RequestFn} role={role}/></QueryClientProvider>);
 return request;
}
it('shows authoritative financing equity and both relation types in an asset modal',async()=>{
 show('MEMBER');
 await userEvent.click((await screen.findAllByRole('button',{name:'详情'}))[0]);
 const dialog=await screen.findByRole('dialog',{name:'家庭车辆 · 资产详情'});
 expect(await within(dialog).findByText('¥50,000.00')).toBeInTheDocument();
 expect(within(dialog).getByText(/抵押关联/)).toBeInTheDocument();
 expect(within(dialog).getByRole('link',{name:'购置融资'})).toHaveAttribute('href','/workspace/loans?loanId=3');
 expect(within(dialog).queryByRole('button',{name:'关联已有贷款'})).not.toBeInTheDocument();
});

it('confirms metadata unlink while protecting the original financed purchase link',async()=>{
 const request=show();await userEvent.click((await screen.findAllByRole('button',{name:'详情'}))[0]);
 await screen.findByRole('link',{name:'抵押借款'});
 expect(screen.getAllByRole('button',{name:'解除关联'})).toHaveLength(1);
 await userEvent.click(screen.getByRole('button',{name:'解除关联'}));
 const confirm=screen.getByRole('dialog',{name:'解除贷款关联？'});
 expect(screen.getAllByRole('dialog')).toHaveLength(1);
 await userEvent.click(within(confirm).getByRole('button',{name:'解除关联'}));
 await waitFor(()=>expect(request).toHaveBeenCalledWith('/api/loans/4/asset-link',expect.objectContaining({method:'PUT',body:{assetId:null,relation:null,expectedAssetId:7,expectedRelation:'COLLATERAL'}})));
});
it('links an existing loan with its previous relationship snapshot and never creates a loan or cash transaction',async()=>{
 const request=show();
 await userEvent.click((await screen.findAllByRole('button',{name:'详情'}))[0]);
 await userEvent.click(await screen.findByRole('button',{name:'关联已有贷款'}));
 await userEvent.selectOptions(await screen.findByLabelText('选择贷款'),'2');
 expect(screen.getByText(/原资产/)).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'保存关联'}));
 await waitFor(()=>expect(request).toHaveBeenCalledWith('/api/loans/2/asset-link',expect.objectContaining({method:'PUT',body:{assetId:7,relation:'FINANCING',expectedAssetId:99,expectedRelation:'COLLATERAL'}})));
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0);
});
