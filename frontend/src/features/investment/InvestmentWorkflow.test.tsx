import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentsPage } from './InvestmentsPage';
import type { RequestFn } from '../common';

const stock = { id: 5, market: 'SZ', tsCode: '000001.SZ', name: '平安银行', active: true, securityType: 'STOCK' };
const account = { id: 3, name: '证券账户', brokerName: '券商', fundingAccountId: 7, currency: 'CNY', status: 'ACTIVE', createdBy: 1, archivedAt: null };
const position = { accountId: 3, accountName: '证券账户', brokerName: '券商', securityId: 5, tsCode: stock.tsCode, name: stock.name, quantity: 100, averageCost: '8.00', cost: '800.00', price: '10.00', marketValue: '1000.00', estimatedValue: '1000.00', realizedProfit: '0.00', unrealizedProfit: '200.00', totalProfit: '200.00', allocationPercent: '100.00', source: 'BAOSTOCK', tradeDate: '2026-09-07', fetchedAt: null, stale: false, error: null, valuationStatus: 'MARKET' };
const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalPages: 1, totalElements: items.length, hasNext: false });
it('opens management in a dialog without replacing filtered holdings',async()=>{
 const {user}=setup();await screen.findAllByRole('button',{name:'平安银行'});
 await user.type(screen.getByRole('searchbox',{name:'搜索持仓'}),'平安');
 await user.click(screen.getByRole('button',{name:'投资管理'}));
 await user.click(await screen.findByRole('menuitem',{name:'账户'}));
 const dialog=screen.getByRole('dialog',{name:'账户管理'});
 expect(within(dialog).getByRole('heading',{name:'投资账户'})).toBeInTheDocument();
 await user.click(within(dialog).getByRole('button',{name:'关闭'}));
 expect(screen.getByRole('searchbox',{name:'搜索持仓'})).toHaveValue('平安');
 await user.click(screen.getByRole('button',{name:'投资管理'}));
 await user.click(await screen.findByRole('menuitem',{name:'汇率'}));
 expect(screen.getByRole('dialog',{name:'汇率管理'})).toBeInTheDocument();
});
it.each([['accounts','账户管理'],['rates','汇率管理']])('keeps the %s deep link as a modal entry',async(tab,title)=>{
 const previous=window.location.href;
 window.history.replaceState(null,'',`/workspace/investments?tab=${tab}`);
 try{setup();expect(screen.getByRole('dialog',{name:title})).toBeInTheDocument();}
 finally{window.history.replaceState(null,'',previous);}
});
it('keeps failed live quotes visible on the dashboard without opening explanatory content',async()=>{
 setup();
 expect(await screen.findByText('报价更新失败')).toBeVisible();
 expect(screen.getByText('保留最近可用数据，请勿将其当作当前成交价。')).toBeVisible();
});
it('opens quotes in a dialog and restores the filtered dashboard after close',async()=>{
 const {user}=setup();
 await screen.findAllByRole('button',{name:'平安银行'});
 await user.type(screen.getByRole('searchbox',{name:'搜索持仓'}),'平安');
 await user.click(screen.getAllByRole('button',{name:'平安银行'})[0]);
 expect(await screen.findByRole('dialog',{name:'证券行情'})).toBeInTheDocument();
 await user.click(within(screen.getByRole('dialog',{name:'证券行情'})).getByRole('button',{name:'关闭行情'}));
 expect(screen.queryByRole('dialog',{name:'证券行情'})).not.toBeInTheDocument();
 expect(screen.getByRole('searchbox',{name:'搜索持仓'})).toHaveValue('平安');
});
function setup(role: 'OWNER'|'MEMBER' = 'OWNER', fixture: { missingFunds?: boolean; retired?: boolean; secondAccount?:boolean; holding?:Record<string,unknown>; unpriced?:boolean } = {}) {
  const writes: unknown[] = [];
  const request: RequestFn = async <T,>(path: string, options?: Parameters<RequestFn>[1]) => {
    if (options?.method === 'POST') { writes.push(options.body); return {} as T; }
    if (path === '/api/portfolio') return { positions: fixture.secondAccount?[position,{...position,accountId:9,accountName:'备用账户'}]:[{...position,...fixture.holding}], totals: { cost: '800.00', estimatedValue: '1000.00', marketValue: '1000.00', realizedProfit: '0.00', unrealizedProfit: '200.00', totalProfit: '200.00', unpricedPositions: fixture.unpriced?1:0 } } as T;
    if (path === '/api/investment-setup') return { completed: true, hasAccounts: true, hasTrades: true } as T;
    if (path.startsWith('/api/investment-accounts')) return page([{ ...account, fundingAccountId: fixture.missingFunds ? null : 7 },...(fixture.secondAccount?[{...account,id:9,name:'备用账户'}]:[])]) as T;
    if (path.startsWith('/api/investment-trades')) return page([]) as T;
    if (path.startsWith('/api/accounts')) return page([{ id: 7, name: '资金卡', type: 'BANK', currency: 'CNY', openingBalance: '100.00', balance: '100.00', availableBalance: '100.00', openingConfirmed: true, openingOn: '2026-01-01', archivedAt: null }]) as T;
    if (path === '/api/market-quotes') return [] as T;
    if (path.endsWith('/catalog-status')) return { state: 'READY', count: 1 } as T;
    if (path.includes('/search')) return page(fixture.retired ? [] : [stock]) as T;
    if (path.includes('/candles')) return { symbol: stock.tsCode, source: 'BAOSTOCK', adjustment: path.endsWith('none') ? 'none' : 'qfq', asOf: null, fetchedAt: null, stale: false, supported: false, bars: [] } as T;
    throw new Error(`Unexpected ${path}`);
  };
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><InvestmentsPage request={request} role={role}/></QueryClientProvider>);
  return { user: userEvent.setup(), writes };
}
it('opens chart immediately after selection and carries the stock into a buy draft without filling execution price', async () => {
  const { user } = setup();
  await user.click(screen.getByRole('button', { name: '行情' }));
  const picker = screen.getByRole('combobox', { name: '证券' });
  await waitFor(() => expect(picker).toHaveAttribute('aria-disabled', 'false'));
  await user.click(picker);
  await user.click(await screen.findByRole('option', { name: /000001.SZ · 平安银行/ }));
  expect(await screen.findByRole('button', { name: '日 K' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '查看 K 线' })).not.toBeInTheDocument();
  await user.click(within(screen.getByRole('dialog',{name:'证券行情'})).getByRole('button', { name: '记录买入平安银行' }));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  expect(within(dialog).getByRole('combobox', { name: '证券' })).toHaveTextContent('平安银行');
  expect(within(dialog).getByLabelText('成交单价')).toHaveValue('');
  expect(within(dialog).getByLabelText('投资账户')).toHaveValue('3');
});
it('prefills a position buy and prevents known cash overdraft before submitting', async () => {
  const { user, writes } = setup();
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录买入平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.type(within(dialog).getByLabelText('数量'), '11');
  await user.type(within(dialog).getByLabelText('成交单价'), '10');
  const submit = within(dialog).getByRole('button', { name: '保存投资记录' });
  expect(submit).toBeDisabled();
  expect(within(dialog).getByText(/余额不足，无法保存/)).toBeInTheDocument();
  await user.click(submit);
  expect(writes).toHaveLength(0);
  await user.clear(within(dialog).getByLabelText('数量'));
  await user.type(within(dialog).getByLabelText('数量'), '10');
  expect(submit).toBeEnabled();
  await user.click(submit);
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({ accountId: 3, securityId: 5, type: 'BUY', quantity: '10', price: '10' });
});
it('shows exact available position and prevents selling more than owned', async () => {
  const { user, writes } = setup();
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录卖出平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  expect(within(dialog).getByText('账内持仓 100 股')).toBeInTheDocument();
  await user.type(within(dialog).getByLabelText('数量'), '100.0001');
  await user.type(within(dialog).getByLabelText('成交单价'), '10');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeDisabled();
  expect(within(dialog).getByText(/卖出数量超过账内持仓/)).toBeInTheDocument();
  expect(writes).toHaveLength(0);
});
it('does not expose contextual trade actions to members', async () => {
  setup('MEMBER');
  await screen.findAllByText('平安银行');
  expect(screen.queryByRole('button', { name: /记录买入|记录卖出|更多操作/ })).not.toBeInTheDocument();
});
it('includes selling fees in the cash guard even when selling creates a net outflow', async () => {
  const { user } = setup();
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录卖出平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.type(within(dialog).getByLabelText('数量'), '1');
  await user.type(within(dialog).getByLabelText('成交单价'), '10');
  await user.clear(within(dialog).getByLabelText('附加费用'));
  await user.type(within(dialog).getByLabelText('附加费用'), '110.01');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeDisabled();
  expect(within(dialog).getByText(/余额不足，无法保存/)).toBeInTheDocument();
  await user.clear(within(dialog).getByLabelText('附加费用'));
  await user.type(within(dialog).getByLabelText('附加费用'), '110');
  const preview = within(dialog).getByRole('region', { name: '账务金额预览' });
  expect(within(preview).queryByText('本次现金流入')).not.toBeInTheDocument();
  expect(within(preview).getByText('本次现金合计')).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeEnabled();
});
it('does not advertise new buys for retired stocks while preserving historic sell entry', async () => {
  const { user } = setup('OWNER', { retired: true });
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  expect(await screen.findByRole('menuitem',{name:'记录卖出平安银行'})).toBeInTheDocument();
  expect(screen.queryByRole('menuitem', { name: '记录买入平安银行' })).not.toBeInTheDocument();
  await user.keyboard('{Escape}');
  await user.click(screen.getAllByRole('button', { name: '平安银行' })[0]);
  await screen.findByRole('button', { name: '日 K' });
  expect(screen.queryByRole('button', { name: '记录买入平安银行' })).not.toBeInTheDocument();
});
it('rejects signed negative-zero fees before the server rejects their syntax', async () => {
  const { user } = setup();
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录买入平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.type(within(dialog).getByLabelText('数量'), '1');
  await user.type(within(dialog).getByLabelText('成交单价'), '10');
  await user.clear(within(dialog).getByLabelText('附加费用'));
  await user.type(within(dialog).getByLabelText('附加费用'), '-0');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeDisabled();
});
it('validates positive opening cost without treating it as a cash outflow', async () => {
  const { user } = setup();
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录买入平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.click(within(dialog).getByText('其他业务 · 期初持仓、分红、费用'));
  await user.selectOptions(within(dialog).getByLabelText('业务类型'), 'OPENING');
  await user.type(within(dialog).getByLabelText('数量'), '1000');
  await user.type(within(dialog).getByLabelText('期初单位成本'), '0');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeDisabled();
  await user.clear(within(dialog).getByLabelText('期初单位成本'));
  await user.type(within(dialog).getByLabelText('期初单位成本'), '10');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeEnabled();
  expect(within(dialog).queryByRole('region', { name: '账务金额预览' })).not.toBeInTheDocument();
});
it('allows opening records without requiring a cash account but blocks cash-moving buys', async () => {
  const { user } = setup('OWNER', { missingFunds: true });
  await user.click(await screen.findByRole('button',{name:'平安银行更多操作'}));
  await user.click(await screen.findByRole('menuitem',{name:'记录买入平安银行'}));
  const dialog = screen.getByRole('dialog', { name: '记一笔投资' });
  await user.type(within(dialog).getByLabelText('数量'), '10');
  await user.type(within(dialog).getByLabelText('成交单价'), '10');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeDisabled();
  await user.click(within(dialog).getByText('其他业务 · 期初持仓、分红、费用'));
  await user.selectOptions(within(dialog).getByLabelText('业务类型'), 'OPENING');
  expect(within(dialog).getByRole('button', { name: '保存投资记录' })).toBeEnabled();
});

it('carries the clicked account through the quote modal when the same security is held twice',async()=>{
 const {user}=setup('OWNER',{secondAccount:true});
 const row=(await screen.findByText('000001.SZ · 备用账户')).closest('tr')!;
 await user.click(within(row).getByRole('button',{name:'平安银行'}));
 const quote=await screen.findByRole('dialog',{name:'证券行情'});
 await user.click(await within(quote).findByRole('button',{name:'记录买入平安银行'}));
 const draft=screen.getByRole('dialog',{name:'记一笔投资'});
 expect(within(draft).getByLabelText('投资账户')).toHaveValue('9');
 expect(within(draft).getByLabelText('成交单价')).toHaveValue('');
});
it('does not carry a holding trade action into another market',async()=>{
 const {user}=setup();
 await user.click((await screen.findAllByRole('button',{name:'平安银行'}))[0]);
 const dialog=screen.getByRole('dialog',{name:'证券行情'});
 expect(within(dialog).getByRole('button',{name:'记录卖出平安银行'})).toBeInTheDocument();
 await user.click(within(dialog).getByRole('button',{name:'港股'}));
 expect(within(dialog).queryByRole('button',{name:'记录卖出平安银行'})).not.toBeInTheDocument();
 await user.click(within(dialog).getByRole('button',{name:'A 股'}));
 expect(within(dialog).queryByRole('button',{name:'记录卖出平安银行'})).not.toBeInTheDocument();
});

it('clears overseas context when missing-price summary opens domestic quotes',async()=>{
 const {user}=setup('OWNER',{unpriced:true,holding:{market:'HK',currency:'HKD',symbol:'00700',tsCode:'00700.HK',name:'騰訊控股',exchange:'HKEX',timezone:'Asia/Hong_Kong'}});
 await user.click((await screen.findAllByRole('button',{name:'騰訊控股'}))[0]);
 await user.click(within(screen.getByRole('dialog',{name:'证券行情'})).getByRole('button',{name:'关闭行情'}));
 await user.click(screen.getByRole('button',{name:'查看行情'}));
 const quote=screen.getByRole('dialog',{name:'证券行情'});
 expect(within(quote).getByRole('button',{name:'A 股'})).toHaveAttribute('aria-pressed','true');
 expect(within(quote).queryByRole('button',{name:'记录卖出騰訊控股'})).not.toBeInTheDocument();
});
