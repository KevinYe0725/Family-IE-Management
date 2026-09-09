import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InvestmentsPage } from './InvestmentsPage';
import type { RequestFn } from '../common';
import type { ApiRequestOptions } from '../../api/client';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
// Matches InvestmentAccountResponse: the future default is fundingAccountId.
const brokerage = { id: 3, name: '证券账户', brokerName: '测试券商', currency: 'CNY', status: 'ACTIVE', createdBy: 7, archivedAt: null, fundingAccountId: 7 };
const accounts = [
  { id: 1, name: '原资金卡', type: 'BANK', currency: 'CNY', openingBalance: '20.30', openingConfirmed: true, openingOn: '2026-01-01', balance: '20.30', availableBalance: '20.30', archivedAt: null },
  { id: 7, name: '新资金卡', type: 'BANK', currency: 'CNY', openingBalance: '100.30', openingConfirmed: true, openingOn: '2026-01-01', balance: '100.30', availableBalance: '100.30', archivedAt: null }
];
const security = { id: 2, market: 'SZ', tsCode: '000001.SZ', name: '平安银行', securityType: 'STOCK', active: true };
// Matches InvestmentTradeResponse: past cash allocation remains cashAccountId.
const historicalTrade = { id: 41, accountId: 3, security, type: 'BUY', quantity: 2, price: '10.00', fee: '0.01', cashImpact: '-20.01', tradedOn: '2026-01-02', createdBy: 7, sourceType: 'MANUAL', sourceId: null, cashAccountId: 1, accountingConfirmed: true };
function setup() {
  const request = vi.fn(async (path: string, options?: ApiRequestOptions) => {
    if (options?.method) return { ...brokerage, ...(options.body as object) };
    if (path.startsWith('/api/investment-accounts')) return page([brokerage]);
    if (path.startsWith('/api/accounts')) return page(accounts);
    if (path.startsWith('/api/investment-trades')) return page([historicalTrade]);
    if (path.startsWith('/api/securities/search')) return page([security]);
    if (path === '/api/market-quotes') return [];
    if (path === '/api/portfolio') return { positions: [], totals: { cost: '0.00', estimatedValue: '0.00', marketValue: '0.00', realizedProfit: '0.00', unrealizedProfit: '0.00', totalProfit: '0.00', unpricedPositions: 0 } };
    throw new Error(`Unexpected request: ${path}`);
  });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><InvestmentsPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  return { user: userEvent.setup(), request };
}

it('creates an investment account with the backend fundingAccountId field', async () => {
  const { user, request } = setup();
  await user.click(screen.getByRole('button',{name:'投资管理'}));
  await user.click(await screen.findByRole('menuitem',{name:'账户'}));
  await user.click(screen.getByRole('button', { name: '新建账户' }));
  const form = within(screen.getByRole('dialog',{name:'新建投资账户'}));
  await user.type(form.getByLabelText('账户名称'), '新证券账户');
  await user.type(form.getByLabelText('券商名称'), '新券商');
  await user.selectOptions(form.getByLabelText('资金账户'), '1');
  await user.click(form.getByRole('button', { name: '保存账户' }));
  expect(request).toHaveBeenCalledWith('/api/investment-accounts', expect.objectContaining({ method: 'POST', body: { name: '新证券账户', brokerName: '新券商', currency: 'CNY', fundingAccountId: 1 }, headers: { 'Idempotency-Key': expect.any(String) } }));
});

it('loads and changes the future funding default from the real account response shape', async () => {
  const { user, request } = setup();
  await user.click(screen.getByRole('button',{name:'投资管理'}));
  await user.click(await screen.findByRole('menuitem',{name:'账户'}));
  await user.click(await screen.findByRole('button', { name: '编辑' }));
  const form = within(screen.getByRole('dialog',{name:'编辑投资账户'}));
  expect(form.getByLabelText('资金账户')).toHaveValue('7');
  await user.selectOptions(form.getByLabelText('资金账户'), '1');
  await user.click(form.getByRole('button', { name: '保存账户' }));
  expect(request).toHaveBeenCalledWith('/api/investment-accounts/3', expect.objectContaining({ method: 'PATCH', body: { name: '证券账户', brokerName: '测试券商', fundingAccountId: 1 } }));
});

it('previews a new buy using the current account funding default and exact cash totals', async () => {
  const { user } = setup();
  await user.click(screen.getByRole('button', { name: '记一笔投资' }));
  const form = within(screen.getByRole('dialog'));
  await user.selectOptions(form.getByLabelText('投资账户'), '3');
  await user.type(form.getByLabelText('数量'), '2');
  await user.type(form.getByLabelText('成交单价'), '10.00');
  await user.clear(form.getByLabelText('附加费用'));
  await user.type(form.getByLabelText('附加费用'), '0.01');
  const preview = within(screen.getByRole('region', { name: '账务金额预览' }));
  expect(preview.getByText('新资金卡')).toBeInTheDocument();
  expect(preview.getByText('预计余额 ¥80.29')).toBeInTheDocument();
});

it('keeps a historical trade on its cashAccountId after the account default changes', async () => {
  const { user } = setup();
  await user.click(screen.getByRole('button', { name: '交易' }));
  await user.click(await screen.findByRole('button', { name: '编辑' }));
  const preview = within(screen.getByRole('region', { name: '账务金额预览' }));
  expect(preview.getByText('原资金卡')).toBeInTheDocument();
  expect(preview.queryByText('新资金卡')).not.toBeInTheDocument();
});
