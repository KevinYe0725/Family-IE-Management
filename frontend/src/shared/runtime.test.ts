import { deferred, localYearMonth, RefreshGate } from './runtime';

it('uses local calendar fields for the selected month', () => {
  const date = { getFullYear: () => 2028, getMonth: () => 0 } as Date;
  expect(localYearMonth(date)).toBe('2028-01');
});

it('does not let an obsolete refresh overwrite the newest result', async () => {
  const gate = new RefreshGate();
  const older = deferred<string>();
  const newer = deferred<string>();
  const committed: string[] = [];

  const olderRun = gate.run(() => older.promise, value => committed.push(value));
  const newerRun = gate.run(() => newer.promise, value => committed.push(value));
  newer.resolve('newest');
  await expect(newerRun).resolves.toEqual({ current: true });
  older.resolve('older');
  await expect(olderRun).resolves.toEqual({ current: false });
  expect(committed).toEqual(['newest']);
});

it('suppresses a late failure after invalidation', async () => {
  const gate = new RefreshGate();
  const pending = deferred<string>();
  const run = gate.run(() => pending.promise, () => undefined);
  gate.invalidate();
  pending.reject(new Error('obsolete request'));
  await expect(run).resolves.toEqual({ current: false });
});
