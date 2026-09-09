import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import type { Account } from '../api/contracts';
import { accountLabel } from './ledger/account-label';
import { money } from './common';
import { AccountIdentity } from './ledger/AccountIdentity';

/** Decimal input is never converted through binary floating-point yuan. */
export function cents(raw: string | null | undefined): bigint | null {
  if (raw == null || !/^-?\d{1,15}(\.\d{1,2})?$/.test(raw.trim())) return null;
  const negative = raw.trim().startsWith('-');
  const [whole, fraction = ''] = raw.trim().replace('-', '').split('.');
  return (BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))) * (negative ? -1n : 1n);
}
export function decimal(value: bigint): string {
  const n = value < 0n ? -value : value;
  return `${value < 0n ? '-' : ''}${n / 100n}.${String(n % 100n).padStart(2, '0')}`;
}
export function sumMoney(...values: Array<string | null | undefined>): string | null {
  const parsed = values.map(cents);
  return parsed.some(value => value === null) ? null : decimal(parsed.reduce<bigint>((total, value) => total + value!, 0n));
}
export function tradeCash(quantity: string, price: string, fee: string, selling = false): string | null {
  if (!/^\d{1,15}(\.\d{1,4})?$/.test(quantity)) return null;
  const [whole, fraction = ''] = quantity.split('.');
  const q = BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'));
  const p = unitPrice(price), f = cents(fee || '0');
  if (p === null || f === null || p < 0n || f < 0n) return null;
  const gross = (q * p + 50000000n) / 100000000n;
  return decimal(selling ? gross - f : gross + f);
}
export function unitPrice(raw:string):bigint|null {
 if(!/^\d{1,12}(\.\d{1,6})?$/.test(raw.trim()))return null;
 const [whole,fraction='']=raw.trim().split('.');return BigInt(whole)*1000000n+BigInt(fraction.padEnd(6,'0'));
}
export function AccountOptions({ accounts }: { accounts: Account[] }) {
  return <>{accounts.map(account => <option key={account.id} value={account.id} disabled={!account.openingConfirmed || Boolean(account.archivedAt)}>{accountLabel(account)}{!account.openingConfirmed ? ' · 待确认期初余额' : ` · 可用 ${money(account.availableBalance,account.currency)}`}</option>)}</>;
}
export function PaymentPreview({ account, amount, incoming = false, adjustment = false, compact = false, showNote = true }: { account?: Account; amount: string | null | undefined; incoming?: boolean; adjustment?: boolean;compact?:boolean;showNote?:boolean }) {
  const balance = account?.openingConfirmed ? cents(account.availableBalance) : null;
  const payment = cents(amount);
  const remaining = balance !== null && payment !== null ? balance + (incoming ? payment : -payment) : null;
  return <section className="payment-preview" aria-label="账务金额预览">
    <div><span>资金账户</span><strong>{account ? <AccountIdentity account={account}/> : '请选择资金账户'}</strong></div>
    <div><span>账内可用余额</span><strong>{balance === null ? '待核对' : money(decimal(balance),account?.currency)}</strong></div>
    <div><span>{adjustment ? '本次更正金额' : incoming ? '本次现金流入' : '本次现金合计'}</span><strong>{money(amount,account?.currency)}</strong></div>
    {!adjustment && <p className="payment-preview__remaining">预计余额 {remaining === null ? '待核对' : money(decimal(remaining),account?.currency)}</p>}
    {!adjustment && !incoming && remaining !== null && (!compact||remaining<0n) && <p role="status">资金缺口 {money(decimal(remaining < 0n ? -remaining : 0n),account?.currency)}</p>}
    {account && !account.openingConfirmed && <p role="status">请先在收支明细 → 账户中确认期初余额，零余额也需要明确确认。</p>}
    {compact&&adjustment&&<p className="source-note">冲回原记录后重新入账，资金账户不变。</p>}
    {!adjustment && remaining !== null && remaining < 0n && <p role="status">账内可用余额不足，请核对资金记录；保存时由服务器检查。</p>}
    {showNote&&!compact&&<small>{adjustment ? '更正会冲回原记录后重新入账，余额由服务器按原资金账户核对。' : '仅预览本系统账本余额；记录不会执行银行或券商转账。'}</small>}
  </section>;
}
export function useFundsRefresh() {
  const cache = useQueryClient();
  return (error: unknown) => {
    if (error instanceof ApiError && ['INSUFFICIENT_FUNDS', 'ACCOUNTING_NOT_INITIALIZED', 'ACCOUNT_ARCHIVED', 'ACCOUNTING_BALANCE_MISMATCH'].includes(error.code ?? '')) {
      void cache.invalidateQueries({ queryKey: ['accounts'], refetchType: 'all' });
    }
  };
}
