import {render,screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {HoldingsDashboard} from './HoldingsDashboard';
import type {PortfolioPosition} from '../../api/contracts';

it('retains the RMB profit in expanded details even when FX reverses the original currency gain',async()=>{
 const p={accountId:1,accountName:'美股账户',securityId:8,tsCode:'BABA.NYSE.US',name:'阿里巴巴',market:'US',symbol:'BABA',currency:'USD',quantity:2,cost:'200.00',price:'110.00',marketValue:'220.00',totalProfit:'20.00',realizedProfit:'0.00',base:{cost:'1500.00',marketValue:'1450.00',totalProfit:'-50.00',fxDate:'2026-09-09'}} as PortfolioPosition;
 render(<HoldingsDashboard positions={[p]} manager={false} verifiedIds={new Set()} onView={()=>{}} onBuy={()=>{}} onSell={()=>{}} priceState={()=>null}/>);
 expect(screen.getByText('USD 110.00')).toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'阿里巴巴持仓明细'}));
 expect(screen.getByText('人民币累计收益')).toBeInTheDocument();
 expect(screen.getByText('-¥50.00')).toBeInTheDocument();
});
