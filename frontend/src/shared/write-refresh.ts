import type { QueryClient } from '@tanstack/react-query';
import type { ApiRequestOptions } from '../api/client';

const summaries = ['dashboard', 'net-worth', 'analysis', 'debt-analysis', 'plugin'];
const ledger = ['transactions', 'accounts', 'accounting-history', 'transfers', 'budget-usage', 'budget-hit', 'budget-entries', 'notifications', ...summaries];
const investments = [...ledger, 'portfolio', 'investment-setup', 'investment-accounts', 'investment-trades', 'investment-plans', 'market-quotes', 'securities', ...summaries];
const dependencies: Record<string, string[]> = {
  transactions: ledger,
  transfers: ledger,
  'fx-transfers': ['fx-transfers',...investments],
  'exchange-rates': ['exchange-rates','exchange-rate-history',...investments],
  accounts: ['recurring-rules', ...ledger],
  'bank-accounts': ['recurring-rules', 'investment-accounts', 'investment-plans', ...ledger],
  categories: ['categories', 'budget-revisions', 'recurring-rules', ...ledger],
  budgets: ['budgets', 'budget-total', 'budget-revisions', ...ledger],
  'budget-templates': ['budget-templates', 'budgets', 'budget-total', 'budget-revisions', ...ledger],
  assets: ['assets', 'asset-valuations', 'loans', ...ledger],
  'investment-accounts': investments,
  'investment-trades': investments,
  'investment-plans': investments,
  'market-quotes': investments,
  securities: investments,
  'investment-setup': ['investment-setup'],
  loans: ['loans', 'loan-schedule', 'loan-prepayments', 'loan-repayments', 'loan-repayment-preview', 'loan-term-options', 'loan-repayment-policy', 'assets', ...ledger],
  'loan-installments': ['loans', 'loan-schedule', 'loan-prepayments', 'loan-repayments', 'loan-repayment-preview', 'loan-term-options', ...ledger],
  'recurring-rules': ['recurring-rules', 'recurring-occurrences', ...ledger],
  'recurring-occurrences': ['recurring-rules', 'recurring-occurrences', ...ledger],
  notifications: ['notifications'],
  family: ['family', 'family-people', 'memberships', 'family-invites', 'members'],
  members: ['members', 'family-people', ...ledger]
};
const pendingRefreshes = new WeakMap<QueryClient, Promise<void>>();

/** Await fresh active AND previously viewed inactive reads after a persisted write. */
export async function refreshAfterWrite(cache: QueryClient, path: string, options: ApiRequestOptions | undefined, isCurrent: () => boolean) {
  if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes((options?.method ?? 'GET').toUpperCase())) return;
  const roots = dependencies[path.split('?')[0].split('/')[2]];
  if (!roots || !isCurrent()) return;
  // Serialize refreshes so concurrent successful writes cannot cancel each
  // other's awaited reads and prematurely finish a saving indicator.
  const refresh = (pendingRefreshes.get(cache) ?? Promise.resolve()).then(async () => {
    if (!isCurrent()) return;
    const filters = { predicate: (query: { queryKey: readonly unknown[] }) => roots.includes(String(query.queryKey[0])) };
    // Cancellation is required even for first reads with no cached data.
    await cache.cancelQueries(filters);
    if (!isCurrent()) return;
    // Query failures remain stale/error in the cache. The write already persisted;
    // the caller must not invite a duplicate submission by labeling it failed.
    await cache.invalidateQueries({ ...filters, refetchType: 'all' }, { throwOnError: false });
  });
  pendingRefreshes.set(cache, refresh);
  try {
    await refresh;
  } finally {
    if (pendingRefreshes.get(cache) === refresh) pendingRefreshes.delete(cache);
  }
}
