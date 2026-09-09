import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoansPage, annualRatePercentError, formatAnnualRatePercent, loanCreatePayload, type LoanDraft } from './LoansPage';
import { FamilyPage } from '../family/FamilyPage';
import type { RequestFn } from '../common';
import { ApiError } from '../../api/client';

const wrap = (node: React.ReactNode) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>;

it('returns server validation to the earlier wizard step and preserves the whole draft', async () => {
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') throw new ApiError('请检查合同', { status: 400, fields: { principal: '本金不符合要求' } });
    return (path === '/api/members' ? [] : { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false }) as T;
  };
  const user = userEvent.setup(); render(wrap(<LoansPage request={request} role="OWNER" />));
  await user.click(screen.getByRole('button', { name: '新建贷款' }));
  await user.selectOptions(screen.getByLabelText('入账方式'), 'OPENING');
  await user.type(screen.getByLabelText('贷款名称'), '保留合同');
  await user.type(screen.getByLabelText('账务起始日剩余本金'), '1000');
  fireEvent.submit(screen.getByLabelText('账务起始日剩余本金').closest('form')!);
  await user.type(screen.getByLabelText('年利率（%）'), '3.6');
  fireEvent.submit(screen.getByLabelText('年利率（%）').closest('form')!);
  fireEvent.submit(screen.getByLabelText('关联资产').closest('form')!);
  await screen.findByText('本金不符合要求');
  expect(screen.getByLabelText('账务起始日剩余本金')).toHaveValue('1000');
  expect(screen.getByLabelText('账务起始日剩余本金')).toHaveFocus();
  expect(screen.getByLabelText('贷款名称')).toHaveValue('保留合同');
  fireEvent.submit(screen.getByLabelText('账务起始日剩余本金').closest('form')!);
  expect(screen.getByLabelText('年利率（%）')).toHaveValue('3.6');
});

it('closes an invite success view cleanly and starts the next invite with defaults', async () => {
  const request: RequestFn = async <T,>(path: string, options?: { method?: string }) => {
    if (options?.method === 'POST') return { token: 'one-time-token', role: 'ADMIN', maxUses: 8, expiresAt: '2026-09-08' } as T;
    if (path === '/api/family') return { id: 1, name: '家庭', status: 'ACTIVE', archivedAt: null } as T;
    if (path === '/api/family/people') return [] as T;
    return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false } as T;
  };
  const user = userEvent.setup(); render(wrap(<FamilyPage request={request} role="OWNER" />));
  await user.click(screen.getByRole('button', { name: '邀请成员' }));
  await user.selectOptions(screen.getByLabelText('邀请角色'), 'ADMIN');
  await user.clear(screen.getByLabelText('最多使用次数'));
  await user.type(screen.getByLabelText('最多使用次数'), '8');
  await user.click(screen.getByRole('button', { name: '创建邀请' }));
  await screen.findByText('邀请已创建');
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '邀请成员' }));
  expect(screen.getByLabelText('邀请角色')).toHaveValue('MEMBER');
  expect(screen.getByLabelText('最多使用次数')).toHaveValue(5);
  await user.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('keeps financial management read-only for members and owner controls exclusive', async () => {
  const request = vi.fn(async (path: string) => {
    if (path.startsWith('/api/loans')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/family') return { id: 1, name: '凯文之家', status: 'ACTIVE', archivedAt: null };
    if (path === '/api/family/people') return [{ memberId: 3, membershipId: 2, name: '成员', relationship: null, accountDisplayName: '成员', email: 'member@example.com', role: 'MEMBER', loginStatus: 'AVAILABLE' }];
    if (path.startsWith('/api/family/memberships')) return { items: [{ id: 2, userId: 8, email: 'member@example.com', displayName: '成员', role: 'MEMBER', status: 'ACTIVE' }], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.startsWith('/api/family/invites')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    throw new Error(`unexpected ${path}`);
  });
  const first = render(wrap(<LoansPage request={request as RequestFn} role="MEMBER" />));
  expect(await screen.findByText('当前为只读协作视图')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '新建贷款' })).not.toBeInTheDocument();
  first.unmount();
  render(wrap(<FamilyPage request={request as RequestFn} role="OWNER" householdName="凯文之家" />));
  expect(await screen.findByRole('button', { name: '归档家庭' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '邀请成员' })).toBeInTheDocument();
});

it('serializes loan form percentages and optional targets for the backend contract', () => {
  const draft: LoanDraft = {
    name: '测试房贷',
    type: 'MORTGAGE',
    linkedAssetId: '',
    memberId: '',
    assignedUserId: '1',
    paymentAccountId: '2',
    paymentCategoryId: '3',
    principal: '100000.00',
    annualRate: '4.9',
    termMonths: '360',
    repaymentMethod: 'EQUAL_PAYMENT',
    startOn: '2026-09-04',
    customSchedule: []
  };

  expect(loanCreatePayload(draft)).toMatchObject({
    linkedAssetId: null,
    memberId: null,
    assignedUserId: 1,
    paymentAccountId: 2,
    paymentCategoryId: 3,
    annualRate: 0.049,
    termMonths: 360,
    customSchedule: null
  });
  expect(loanCreatePayload({ ...draft, annualRate: '3.6' }).annualRate).toBe(0.036);
  expect(loanCreatePayload({ ...draft, annualRate: '3.1' }).annualRate).toBe(0.031);
});

it('validates annual rates as percent input before building a request payload', () => {
  const draft: LoanDraft = {
    name: '测试房贷', type: 'MORTGAGE', linkedAssetId: '', memberId: '', assignedUserId: '1',
    paymentAccountId: '2', paymentCategoryId: '3', principal: '100000.00', annualRate: '3.12345',
    termMonths: '360', repaymentMethod: 'EQUAL_PAYMENT', startOn: '2026-09-04', customSchedule: []
  };

  expect(annualRatePercentError('3.1234')).toBeNull();
  expect(annualRatePercentError('3.12345')).toContain('百分比');
  expect(annualRatePercentError('100.0001')).toContain('0 到 100');
  expect(() => loanCreatePayload(draft)).toThrow(/百分比/);
});

it('renders the stored fractional annual rate as a user-facing percentage', () => {
  expect(formatAnnualRatePercent('0.049000')).toBe('4.9');
});

it('pages through a long loan schedule to the final installment', async () => {
  const loan = { accountingInitialized: true, fundingMode: 'OPENING', accountingOn: '2026-01-01', lastPaymentOn: null, id: 4, name: '三十年房贷', type: 'MORTGAGE', linkedAssetId: null, memberId: null, assignedUserId: 7, paymentAccountId: 1, paymentCategoryId: 2, principal: '1000000.00', annualRate: '0.049000', termMonths: 360, repaymentMethod: 'EQUAL_PAYMENT', startOn: '2026-09-01', currentPrincipal: '1000000.00', status: 'ACTIVE' };
  const request = vi.fn(async (path: string) => {
    if (path === '/api/loans/4') return loan;
    if (path.startsWith('/api/loans?')) return { items: [loan], page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false };
    if (path.includes('/schedule')) {
      const page = Number(new URLSearchParams(path.split('?')[1]).get('page'));
      const start = page * 50 + 1;
      const count = page === 7 ? 10 : 50;
      return { items: Array.from({ length: count }, (_, index) => ({ id: start + index, installmentNo: start + index, dueOn: '2026-10-01', principal: '1.00', interest: '1.00', status: 'PENDING', confirmedTransactionId: null })), page, size: 50, totalElements: 360, totalPages: 8, hasNext: page < 7 };
    }
    if (path.startsWith('/api/accounts') || path.startsWith('/api/assets') || path.startsWith('/api/family/memberships')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path.startsWith('/api/categories')) return { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false };
    if (path === '/api/members') return [];
    throw new Error(`unexpected ${path}`);
  });
  const user = userEvent.setup();
  render(wrap(<LoansPage request={request as RequestFn} role="MEMBER" userId={7} />));

  await user.click(await screen.findByRole('button', { name: '查看计划' }));
  const scheduleDialog = await screen.findByRole('dialog', { name: /还款计划/ });
  const pager = () => within(scheduleDialog).getByRole('navigation', { name: '还款计划分页' });
  for (let page = 1; page < 8; page += 1) {
    await user.click(within(pager()).getByRole('button', { name: '下一页' }));
    await within(scheduleDialog).findByText(`第 ${page + 1} / 8 页`);
  }
  expect(within(scheduleDialog).getByText('360')).toBeInTheDocument();
  expect(within(pager()).getByRole('button', { name: '下一页' })).toBeDisabled();
}, 10_000);
