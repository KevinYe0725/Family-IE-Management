import { QueryClient } from '@tanstack/react-query';
import { refreshAfterWrite } from './write-refresh';

it.each(['/api/assets/42/sale-preview', '/api/assets/42/sale-preview?source=dialog'])('leaves current reads intact after read-only POST %s', async path => {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  for (const key of ['assets', 'accounts', 'loans', 'transactions']) cache.setQueryData([key], { version: 1 });
  await refreshAfterWrite(cache, path, { method: 'POST' }, () => true);
  for (const key of ['assets', 'accounts', 'loans', 'transactions']) {
    expect(cache.getQueryState([key])?.isInvalidated).toBe(false);
    expect(cache.getQueryData([key])).toEqual({ version: 1 });
  }
});

it.each(['/api/assets/42/sale', '/api/assets/42/dispose', '/api/assets/42/sale-preview/confirm'])('still refreshes persisted asset writes at %s', async path => {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  cache.setQueryData(['transactions'], { version: 1 });
  await refreshAfterWrite(cache, path, { method: 'POST' }, () => true);
  expect(cache.getQueryState(['transactions'])?.isInvalidated).toBe(true);
});

it('refreshes every affected loan read after a confirmed asset sale', async () => {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const keys = [
    ['assets', 'financing', 42], ['loans'], ['loan-schedule', 8], ['loan-prepayments', 8],
    ['loan-repayments', 8], ['loan-repayment-preview', 8], ['loan-term-options', 8], ['loan-repayment-policy', 8]
  ];
  for (const key of keys) cache.setQueryData(key, { version: 1 });

  await refreshAfterWrite(cache, '/api/assets/42/sale', { method: 'POST' }, () => true);

  for (const key of keys) expect(cache.getQueryState(key)?.isInvalidated).toBe(true);
});
