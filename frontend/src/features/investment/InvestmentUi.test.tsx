import {render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {InvestmentActions,InvestmentValuationStatus} from './investment-ui';

it('shows quote failure without opening calculation explanations',()=>{
 render(<InvestmentValuationStatus failed busy={false} onRefresh={()=>{}}/>);
 expect(screen.getByRole('status')).toHaveTextContent('报价更新失败');
 expect(screen.getByRole('status')).toBeVisible();
 expect(screen.getByRole('button',{name:'重试报价'})).toBeVisible();
});
it('does not show a large warning for ordinary closing or delayed quotes',()=>{
 render(<InvestmentValuationStatus failed={false} busy={false} onRefresh={()=>{}}/>);
 expect(screen.queryByRole('status')).not.toBeInTheDocument();
 expect(screen.queryByText('部分持仓不是即时价格')).not.toBeInTheDocument();
});
it('keeps context actions behind an explicit menu and dispatches only the selected action',async()=>{
 const buy=vi.fn(),sell=vi.fn();
 render(<InvestmentActions label="平安银行更多操作" actions={[{label:'记录买入平安银行',onClick:buy},{label:'记录卖出平安银行',onClick:sell}]}/>);
 expect(screen.queryByRole('menuitem')).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole('button',{name:'平安银行更多操作'}));
 await userEvent.click(await screen.findByRole('menuitem',{name:'记录卖出平安银行'}));
 expect(sell).toHaveBeenCalledOnce();expect(buy).not.toHaveBeenCalled();
});
it('opens by keyboard and dismisses its menu with Escape',async()=>{
 render(<InvestmentActions label="更多" actions={[{label:'查看记录',onClick:()=>{}}]}/>);
 const trigger=screen.getByRole('button',{name:'更多'});trigger.focus();
 await userEvent.keyboard('{Enter}');expect(await screen.findByRole('menuitem',{name:'查看记录'})).toBeInTheDocument();
 await userEvent.keyboard('{Escape}');await waitFor(()=>expect(screen.queryByRole('menuitem')).not.toBeInTheDocument());
});
