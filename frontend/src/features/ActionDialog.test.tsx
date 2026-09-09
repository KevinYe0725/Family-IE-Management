import {useState} from 'react';
import {render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {ActionDialog,FormError} from './common';
import {ApiError} from '../api/client';
import {DateField} from '../shared/DateField';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AccountingHistory} from './ledger/accounting-flows';
import type {RequestFn} from './common';

it('protects unsaved input when closing a centered action dialog',async()=>{
 function Form(){const [open,setOpen]=useState(true),[value,setValue]=useState('');return <ActionDialog open={open} title="编辑记录" draft={{value}} onClose={()=>setOpen(false)}><label>金额<input value={value} onChange={e=>setValue(e.target.value)}/></label></ActionDialog>;}
 render(<Form/>);const user=userEvent.setup();
 await user.type(screen.getByLabelText('金额'),'120');
 await user.click(screen.getByRole('button',{name:'关闭'}));
 expect(screen.getByRole('dialog',{name:'放弃未保存的修改？'})).toBeInTheDocument();
 await user.click(screen.getByRole('button',{name:'继续编辑'}));
 expect(screen.getByLabelText('金额')).toHaveValue('120');
});
it('closes only the top action dialog with Escape and restores focus',async()=>{
 function Form(){const [child,setChild]=useState(false);return <><ActionDialog open title="账户" onClose={()=>{}}><button onClick={()=>setChild(true)}>新增资金账户</button></ActionDialog><ActionDialog open={child} title="资金" onClose={()=>setChild(false)}><input aria-label="资金名称"/></ActionDialog></>;}
 render(<Form/>);const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'新增资金账户'}));
 await user.keyboard('{Escape}');
 expect(screen.queryByRole('dialog',{name:'资金'})).not.toBeInTheDocument();
 expect(within(screen.getByRole('dialog',{name:'账户'})).getByRole('button',{name:'新增资金账户'})).toHaveFocus();
});
it('prevents dismissal and editing during a save',async()=>{
 const close=vi.fn();render(<ActionDialog open busy title="正在保存" onClose={close}><input aria-label="金额"/></ActionDialog>);
 expect(screen.getByLabelText('金额')).toBeDisabled();
 expect(screen.getByRole('button',{name:'关闭'})).toBeDisabled();
 await userEvent.keyboard('{Escape}');expect(close).not.toHaveBeenCalled();
});
it('keeps actionable errors visible while moving diagnostic IDs behind disclosure',async()=>{
 render(<FormError compact error={new ApiError('余额不足',{status:409,requestId:'diagnostic-123'})}/>);
 expect(screen.getByRole('alert')).toHaveTextContent('余额不足');
 expect(screen.getByText('请求 ID：diagnostic-123')).not.toBeVisible();
 await userEvent.click(screen.getByText('错误详情'));
 expect(screen.getByText('请求 ID：diagnostic-123')).toBeVisible();
});
it('keeps the calendar inside the action dialog and closes it before the parent',async()=>{
 const close=vi.fn();render(<ActionDialog open title="选择日期" onClose={close}><label>日期<DateField value="2026-09-09" onChange={()=>{}}/></label></ActionDialog>);
 await userEvent.click(screen.getByLabelText('日期'));
 expect((await within(screen.getByRole('dialog',{name:'选择日期'})).findAllByRole('gridcell')).length).toBeGreaterThan(0);
 await userEvent.keyboard('{Escape}');
 expect(screen.queryAllByRole('gridcell')).toHaveLength(0);expect(close).not.toHaveBeenCalled();
});
it('allows keyboard traversal from diagnostic disclosure back into correction fields',async()=>{
 render(<ActionDialog open title="更正金额" onClose={()=>{}}><FormError compact error={new ApiError('请核对金额',{status:422,requestId:'trace-id'})}/><label>更正金额<input/></label><button>保存</button></ActionDialog>);
 const user=userEvent.setup();screen.getByText('错误详情').focus();
 await user.tab();expect(screen.getByRole('textbox',{name:'更正金额'})).toHaveFocus();
});
it('makes compact audit entries keyboard reachable when source filters are hidden',async()=>{
 const request=(async()=>({page:0,size:20,totalElements:1,totalPages:1,hasNext:false,items:[{journalId:1,sourceType:'INVESTMENT_TRADE',sourceId:7,operation:'POST',revision:1,effectiveOn:'2026-09-09',recordedAt:'2026-09-09T02:00:00Z',actorId:7,reversesJournalId:null,legs:[]}]})) as RequestFn;
 render(<QueryClientProvider client={new QueryClient()}><ActionDialog open title="投资历史" onClose={()=>{}}><AccountingHistory compact request={request} source={{sourceType:'INVESTMENT_TRADE',sourceId:7}}/></ActionDialog></QueryClientProvider>);
 const summary=(await screen.findByText('入账',{selector:'strong'})).closest('summary');
 screen.getByRole('button',{name:'关闭'}).focus();await userEvent.tab();
 expect(summary).toHaveFocus();
});
