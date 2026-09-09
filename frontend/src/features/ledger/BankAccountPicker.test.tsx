import {render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {BankAccountPicker} from './BankAccountPicker';
import {useState} from 'react';
const child=(id:number,currency:string)=>({id,name:`汇丰 ${currency}`,type:'BANK' as const,currency,bankAccountId:10,bankAccountName:'汇丰 One',bankName:'汇丰',cardLastFour:'1234',openingBalance:'100.00',openingOn:'2026-01-01',openingConfirmed:true,archivedAt:null,balance:'100.00',availableBalance:'100.00'});
function wrap(node:React.ReactNode){return <QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}>{node}</QueryClientProvider>;}
it('selects the matching currency child instead of the bank parent id',async()=>{
 const onChange=vi.fn();render(wrap(<BankAccountPicker label="资金账户" accounts={[child(1,'CNY'),child(2,'USD')]} currency="USD" value="" onChange={onChange}/>));
 await userEvent.selectOptions(screen.getByLabelText('资金账户'),'2');
 expect(onChange).toHaveBeenCalledWith('2');expect(screen.getAllByRole('option',{name:/汇丰 One/})).toHaveLength(1);
});
it('switches the selected bank currency without choosing another bank',async()=>{
 const onChange=vi.fn();const accounts=[child(1,'CNY'),child(2,'USD')];
 const ui=(currency:string)=>wrap(<BankAccountPicker label="资金账户" accounts={accounts} currency={currency} value="1" onChange={onChange}/>);
 const view=render(ui('CNY'));view.rerender(ui('USD'));
 await waitFor(()=>expect(onChange).toHaveBeenCalledWith('2'));
});
it('clears unavailable currency instead of falling back to another account',async()=>{
 const onChange=vi.fn();render(wrap(<BankAccountPicker label="资金账户" accounts={[child(1,'CNY')]} currency="USD" value="1" onChange={onChange}/>));
 await waitFor(()=>expect(onChange).toHaveBeenCalledWith(''));
 expect(screen.getByText('此卡尚未添加 USD 余额')).toBeInTheDocument();
});
it('keeps the newly created currency selected before the parent query refreshes',async()=>{
 const outerSubmit=vi.fn();
 const request=vi.fn(async()=>({id:10,name:'汇丰 One',bankName:'汇丰',cardLastFour:'1234',archivedAt:null,accounts:[child(1,'CNY'),child(2,'USD')]}));
 function Form(){const [value,setValue]=useState('');return <form onSubmit={e=>{e.preventDefault();outerSubmit();}}><BankAccountPicker label="资金账户" canManage accounts={[child(1,'CNY')]} request={request as any} currency="USD" value={value} onChange={setValue}/></form>;}
 render(wrap(<Form/>));const user=userEvent.setup();await user.selectOptions(screen.getByLabelText('资金账户'),'bank:10');await user.click(screen.getByRole('button',{name:'添加 USD 余额'}));
 await user.type(screen.getByLabelText('USD 期初余额'),'0');await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'保存余额'}));
 await waitFor(()=>expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
 expect(screen.getByLabelText('资金账户')).toHaveValue('2');
 expect(outerSubmit).not.toHaveBeenCalled();
});
it('retains the chosen bank when returning from a missing currency',async()=>{
 function Form(){const [value,setValue]=useState('1');return <BankAccountPicker label="资金账户" accounts={[child(1,'CNY')]} value={value} onChange={setValue}/>;}
 render(wrap(<Form/>));const user=userEvent.setup();await user.selectOptions(screen.getByLabelText('资金账户币种'),'USD');
 await user.selectOptions(screen.getByLabelText('资金账户币种'),'CNY');
 await waitFor(()=>expect(screen.getByLabelText('资金账户')).toHaveValue('1'));
});
it('does not offer admin-only balance creation to ordinary members',async()=>{
 render(wrap(<BankAccountPicker label="账户" accounts={[child(1,'CNY')]} currency="USD" value="" request={vi.fn() as any} canManage={false} onChange={()=>{}}/>));
 await userEvent.selectOptions(screen.getByLabelText('账户'),'bank:10');
 expect(screen.queryByRole('button',{name:'添加 USD 余额'})).not.toBeInTheDocument();
 expect(screen.getByText('请管理员添加此币种余额。')).toBeInTheDocument();
});
it('clears an optional account when it is no longer available',async()=>{
 const onChange=vi.fn();const ui=(accounts:any[])=>wrap(<BankAccountPicker label="账户" required={false} accounts={accounts} value="1" onChange={onChange}/>);
 const view=render(ui([child(1,'CNY')]));view.rerender(ui([]));
 await waitFor(()=>expect(onChange).toHaveBeenCalledWith(''));
});
it('preserves an existing child id while account options are still loading',async()=>{
 const onChange=vi.fn();const view=render(wrap(<BankAccountPicker accountsReady={false} label="账户" accounts={[]} value="1" onChange={onChange}/>));
 expect(onChange).not.toHaveBeenCalled();expect(screen.getByLabelText('账户')).toBeInvalid();
 view.rerender(wrap(<BankAccountPicker accountsReady label="账户" accounts={[child(1,'CNY')]} value="1" onChange={onChange}/>));
 expect(screen.getByLabelText('账户')).toHaveValue('1');expect(onChange).not.toHaveBeenCalled();
});
it('blocks form submission while the account list is loading',async()=>{
 const submitted=vi.fn();render(wrap(<form onSubmit={e=>{e.preventDefault();submitted();}}><BankAccountPicker accountsReady={false} label="账户" accounts={[]} value="" onChange={()=>{}}/><button type="submit">保存</button></form>));
 await userEvent.click(screen.getByRole('button',{name:'保存'}));expect(submitted).not.toHaveBeenCalled();
});
it('does not require an account for a zero-cash operation during loading',()=>{
 render(wrap(<BankAccountPicker required={false} accountsReady={false} label="账户" accounts={[]} value="" onChange={()=>{}}/>));
 expect(screen.getByLabelText('账户')).toBeValid();
});
it('keeps cash and wallet identifiers independent from bank groups',async()=>{
 const onChange=vi.fn();render(wrap(<BankAccountPicker label="账户" currency="CNY" accounts={[{...child(3,'CNY'),type:'CASH',bankAccountId:null,bankAccountName:null,name:'现金'},{...child(4,'CNY'),type:'WALLET',bankAccountId:null,bankAccountName:null,name:'支付宝'}]} value="" onChange={onChange}/>));
 await userEvent.selectOptions(screen.getByLabelText('账户'),'4');expect(onChange).toHaveBeenCalledWith('4');expect(screen.getByRole('option',{name:/现金/})).toBeInTheDocument();
});
