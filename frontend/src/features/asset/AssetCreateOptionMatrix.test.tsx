import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AssetsPage } from './AssetsPage';
import type { RequestFn } from '../common';

const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length ? 1 : 0, hasNext: false });
const accounts = [
  { id: 1, name: '工资卡', currency: 'CNY', openingConfirmed: true, openingOn: '2026-01-01', availableBalance: '8000.00', balance: '8000.00', archivedAt: null },
  { id: 2, name: '港币卡', currency: 'HKD', openingConfirmed: true, openingOn: '2026-01-01', availableBalance: '0.00', balance: '0.00', archivedAt: null }
];
const categories = [{ id: 5, name: '餐饮', kind: 'expense' }, { id: 6, name: '工资', kind: 'income' }];

function stubRequest() {
  return vi.fn(async (path: string, opts?: any) => {
    if (opts?.method) return {};
    if (path === '/api/members') return [];
    if (path === '/api/net-worth') return { asset: '1000.00' };
    if (path.startsWith('/api/accounts')) return page(accounts);
    if (path.startsWith('/api/categories')) return page(categories);
    return page([]);
  });
}
function postedBody(request: any, path: string): any {
  const calls = request.mock.calls as [string, { method?: string; body: any }][];
  const call = calls.find(([p, o]) => p === path && o?.method === 'POST');
  expect(call, `expected POST ${path}`).toBeTruthy();
  return (call as [string, { body: any }])[1].body;
}
async function renderCreate(assetType = 'OTHER') {
  const request = stubRequest();
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={cache}><AssetsPage request={request as RequestFn} role="OWNER" /></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: '新建资产' }));
  const drawer = within((await screen.findByRole('heading', { name: '新建资产' })).closest('aside') as HTMLElement);
  await user.selectOptions(drawer.getByLabelText('资产类型'), assetType);
  return { user, request, drawer };
}

it('期初资产组合：只登记已有资产，购买/贷款信息一概不出现', async () => {
  const { user, request, drawer } = await renderCreate();
  expect(drawer.getByLabelText('入账方式')).toHaveValue('OPENING');
  expect(drawer.queryByLabelText('资金账户')).not.toBeInTheDocument();
  expect(drawer.queryByLabelText('付款账户')).not.toBeInTheDocument();
  expect(drawer.queryByLabelText(/年利率/)).not.toBeInTheDocument();
  expect(drawer.queryAllByText(/首付/)).toHaveLength(0);
  await user.type(drawer.getByLabelText('资产名称'), '祖传字画');
  await user.type(drawer.getByLabelText('当前价值'), '3000');
  expect(drawer.getByLabelText('购入日期')).not.toBeRequired();
  await user.click(drawer.getByRole('button', { name: '保存资产' }));
  const body = postedBody(request, '/api/assets');
  expect(body).toMatchObject({ accountingMode: 'OPENING', name: '祖传字画', currentValue: '3000' });
  expect(body.purchaseValue).toBeNull();
  expect(body.acquiredOn).toBeNull();
  expect(body.fundingAccountId).toBeNull();
  expect(Object.keys(body)).not.toContain('principal');
  expect(Object.keys(body)).not.toContain('downPaymentAccountId');
});

it('现金购入组合：购入日期/价格与资金账户同时必填，付款到账户', async () => {
  const { user, request, drawer } = await renderCreate();
  await user.selectOptions(drawer.getByLabelText('入账方式'), 'PURCHASE');
  expect(drawer.getByLabelText('资金账户')).toBeRequired();
  expect(drawer.getByText(/购入价值计为现金付款/)).toBeInTheDocument();
  expect(drawer.queryByLabelText('付款账户')).not.toBeInTheDocument();
  await user.type(drawer.getByLabelText('资产名称'), '金条');
  await user.type(drawer.getByLabelText('购入价值'), '8000');
  await user.type(drawer.getByLabelText('当前价值'), '8000');
  fireEvent.change(drawer.getByLabelText('购入日期'), { target: { value: '2026-01-10' } });
  await user.selectOptions(drawer.getByLabelText('资金账户'), '1');
  await user.click(drawer.getByRole('button', { name: '保存资产' }));
  const body = postedBody(request, '/api/assets');
  expect(body).toMatchObject({ accountingMode: 'PURCHASE', name: '金条', purchaseValue: '8000', acquiredOn: '2026-01-10', fundingAccountId: 1, currentValue: '8000' });
});

it('贷款购买组合：只保留“贷款本金=购入价值”，杜绝首付差额字段', async () => {
  const { user, request, drawer } = await renderCreate('VEHICLE');
  await user.selectOptions(drawer.getByLabelText('入账方式'), 'FINANCED_PURCHASE');
  // 通用购入日期/当前价值字段在此模式下隐藏（资产按账务日期与购入价值入账）。
  expect(drawer.queryByLabelText('购入日期')).not.toBeInTheDocument();
  expect(drawer.queryByLabelText('当前价值')).not.toBeInTheDocument();
  expect(drawer.queryByLabelText(/首付资金账户/)).not.toBeInTheDocument();
  expect(drawer.queryByLabelText('贷款本金')).not.toBeInTheDocument();
  // 帮助文案允许提到“无首付”，但绝不能出现可输入的首付账户选项。
  expect(drawer.queryByLabelText(/首付资金账户/)).not.toBeInTheDocument();
  expect(drawer.queryByText(/无（贷款金额=购入价值）/)).not.toBeInTheDocument();
  expect(drawer.getByText(/贷款本金固定等于购入价值/)).toBeInTheDocument();
  const purchase = drawer.getByLabelText('购入价值（即贷款本金）');
  expect(purchase).toBeRequired();
  expect(drawer.getByLabelText('年利率（%）')).toBeInTheDocument();
  expect(drawer.getByLabelText('贷款期数（月）')).toBeInTheDocument();
  expect(drawer.getByLabelText('还款方式')).toHaveValue('EQUAL_PAYMENT');
  expect(drawer.getByLabelText('付款账户')).toBeRequired();
  expect(drawer.getByLabelText('支出分类')).toBeRequired();
  await user.type(drawer.getByLabelText('资产名称'), '特斯拉');
  await user.type(purchase, '150000');
  await user.type(drawer.getByLabelText('年利率（%）'), '5.5');
  await user.type(drawer.getByLabelText('贷款期数（月）'), '36');
  await user.type(drawer.getByLabelText('品牌型号'), 'Model 3');
  await user.selectOptions(drawer.getByLabelText('付款账户'), '1');
  await user.selectOptions(drawer.getByLabelText('支出分类'), '5');
  await user.click(drawer.getByRole('button', { name: '保存资产' }));
  const body = postedBody(request, '/api/loans');
  expect(body).toMatchObject({
    name: '特斯拉', type: 'CAR', fundingMode: 'FINANCED_PURCHASE', createPurchasedAsset: true,
    principal: '150000', annualRate: 0.055, termMonths: 36, repaymentMethod: 'EQUAL_PAYMENT'
  });
  expect(body.purchasedAsset).toMatchObject({ name: '特斯拉', ownerMemberId: null, purchaseValue: '150000' });
  expect(Object.keys(body)).not.toContain('downPaymentAccountId');
  expect(Object.keys(body)).not.toContain('loanPrincipal');
});

it('选项切换组合：入账方式来回切换时各板块字段随模式出现/消失且不残留首付概念', async () => {
  const { user, drawer } = await renderCreate();
  for (const mode of ['OPENING', 'PURCHASE', 'FINANCED_PURCHASE', 'OPENING', 'FINANCED_PURCHASE'] as const) {
    await user.selectOptions(drawer.getByLabelText('入账方式'), mode);
    expect(drawer.queryByLabelText(/首付资金账户/)).not.toBeInTheDocument();
    expect(Boolean(drawer.queryByLabelText('资金账户'))).toBe(mode === 'PURCHASE');
    expect(Boolean(drawer.queryByLabelText('付款账户'))).toBe(mode === 'FINANCED_PURCHASE');
    expect(Boolean(drawer.queryByLabelText('当前价值'))).toBe(mode !== 'FINANCED_PURCHASE');
    expect(Boolean(drawer.queryByLabelText('购入价值（即贷款本金）'))).toBe(mode === 'FINANCED_PURCHASE');
  }
});
