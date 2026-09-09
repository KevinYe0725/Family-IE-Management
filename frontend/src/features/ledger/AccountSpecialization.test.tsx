import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { TransactionsPage } from './TransactionsPage';
import { AccountOptions } from '../accounting';
import type { Account } from '../../api/contracts';
import type { RequestFn } from '../common';

describe('account specialization', () => {
  it('shows explicit wallet and bank identities with initialization restrictions', () => {
    const base = { currency: 'CNY', openingBalance: '0', archivedAt: null, openingOn: null, balance: '0', availableBalance: '0' };
    render(<select><AccountOptions accounts={[
      { ...base, id: 1, name: '日常', type: 'WALLET', walletProvider: 'ALIPAY', openingConfirmed: true },
      { ...base, id: 2, name: '储蓄', type: 'BANK', bankName: '招商银行', cardLastFour: '0123', openingConfirmed: false },
    ] as Account[]} /></select>);
    expect(screen.getByRole('option', { name: /日常 · 支付宝余额/ })).toBeEnabled();
    expect(screen.getByRole('option', { name: /储蓄 · 银行卡 · 招商银行 · 尾号 0123.*待确认/ })).toBeDisabled();
  });

  it('edits only metadata and clears bank details when selecting a wallet', async () => {
    const request = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
      if(path==='/api/bank-accounts')return [];
      if (options?.method === 'PATCH') return {};
      if (path === '/api/members') return [];
      const items = path.startsWith('/api/accounts') ? [{ id: 2, name: '日常', type: 'BANK', bankName: '招商银行', cardLastFour: '0123', currency: 'CNY', openingBalance: '100.00', openingConfirmed: true, openingOn: '2026-01-01', balance: '100.00', availableBalance: '100.00', archivedAt: null }] : [];
      return { items, page: 0, size:50, totalPages: items.length?1:0, totalElements: items.length, hasNext: false };
    });
    render(<QueryClientProvider client={new QueryClient()}><TransactionsPage request={request as RequestFn} role="OWNER" userId={1} requestedSection="accounts" /></QueryClientProvider>);
    await userEvent.click(await screen.findByRole('button', { name: '编辑' }));
    expect(screen.getByLabelText('银行卡尾号（选填）')).toHaveValue('0123');
    await userEvent.click(screen.getByRole('radio', { name: '微信' }));
    expect(screen.queryByLabelText('银行卡尾号（选填）')).not.toBeInTheDocument();
    fireEvent.submit(screen.getByLabelText('账户名称').closest('form')!);
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/accounts/2', expect.objectContaining({
      method: 'PATCH', body: { name: '日常', type: 'WALLET', walletProvider: 'WECHAT', bankName: '', cardLastFour: '' }
    })));
  });
});
