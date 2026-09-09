import {render,screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AuthContext,type AuthContextValue} from '../../auth/AuthProvider';
import {PinnedStockCard} from './PinnedStockCard';
import type {RequestFn} from '../common';
vi.mock('./TradeStockPicker',()=>({TradeStockPicker:({onChange}:{onChange:(s:unknown)=>void})=><button onClick={()=>onChange({id:7,tsCode:'000001.SZ',name:'平安银行',market:'SZ',currency:'CNY'})}>选择平安银行</button>}));
vi.mock('./StockChart',()=>({StockChart:({security}:{security:{name:string}})=><div>常驻图表：{security.name}</div>}));
const request=(async()=>({quotes:[],nextRefreshSeconds:60})) as RequestFn;
function show(page:'home'|'investments'='home',userId=1){return render(<AuthContext.Provider value={{session:{userId,householdId:2}} as AuthContextValue}><QueryClientProvider client={new QueryClient()}><PinnedStockCard page={page} request={request}/></QueryClientProvider></AuthContext.Provider>);}
beforeEach(()=>localStorage.clear());
it('persists a chosen stock across remounts and allows removing it',async()=>{
 const view=show();await userEvent.click(screen.getByRole('button',{name:'固定一只股票'}));await userEvent.click(screen.getByRole('button',{name:'选择平安银行'}));await userEvent.click(screen.getByRole('button',{name:'固定展示'}));
 expect(await screen.findByText('常驻图表：平安银行')).toBeInTheDocument();view.unmount();
 show();expect(screen.getByText('常驻图表：平安银行')).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'取消固定'}));expect(screen.queryByText('常驻图表：平安银行')).not.toBeInTheDocument();
});
it('isolates the two pages and different accounts',async()=>{
 let view=show();await userEvent.click(screen.getByRole('button',{name:'固定一只股票'}));await userEvent.click(screen.getByRole('button',{name:'选择平安银行'}));await userEvent.click(screen.getByRole('button',{name:'固定展示'}));view.unmount();
 view=show('investments');expect(screen.queryByText('常驻图表：平安银行')).not.toBeInTheDocument();view.unmount();
 show('home',3);expect(screen.queryByText('常驻图表：平安银行')).not.toBeInTheDocument();
});
