import assert from 'node:assert/strict';
import test from 'node:test';
import { createInitialState, localYearMonth } from '../../main/resources/static/ui-state.js';

test('local year-month pads January', () => {
  assert.equal(localYearMonth(new Date(2027, 0, 15, 12)), '2027-01');
});

test('local year-month renders October without extra padding', () => {
  assert.equal(localYearMonth(new Date(2027, 9, 1, 12)), '2027-10');
});

test('initial state derives the month from local date getters', () => {
  const localDate = {
    getFullYear: () => 2028,
    getMonth: () => 0,
    toISOString: () => '1999-12-31T16:00:00.000Z'
  };

  const state = createInitialState(localDate);

  assert.equal(state.month, '2028-01');
  assert.deepEqual(state.data.accounts, []);
  assert.deepEqual(state.transactionPage, {
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasNext: false
  });
  state.month = '2026-09';
  assert.equal(state.month, '2026-09');
});
