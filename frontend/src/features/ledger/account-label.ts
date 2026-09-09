import type { Account } from '../../api/contracts';

export function accountDescription(account: Pick<Account, 'type' | 'walletProvider' | 'bankName' | 'cardLastFour'>): string {
  if (account.type === 'CASH') return '现金';
  if (account.type === 'BANK') return ['银行卡', account.bankName, account.cardLastFour ? `尾号 ${account.cardLastFour}` : null].filter(Boolean).join(' · ');
  return account.walletProvider === 'ALIPAY' ? '支付宝余额' : account.walletProvider === 'WECHAT' ? '微信余额' : account.walletProvider === 'OTHER' ? '其他电子钱包' : '电子钱包（未细分）';
}
export function accountLabel(account: Account): string {
  return `${account.bankAccountName??account.name} · ${accountDescription(account)}${account.bankAccountId?` · ${account.currency}`:''}`;
}
