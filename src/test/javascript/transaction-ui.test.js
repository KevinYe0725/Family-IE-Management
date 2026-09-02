import assert from 'node:assert/strict';
import test from 'node:test';
import {
  configureAccountSelect,
  normalizeActiveAccounts,
  readTransactionPage,
  transactionPayload
} from '../../main/resources/static/transaction-ui.js';

function selectHarness() {
  const options = [];
  const ownerDocument = {
    createElement() {
      return { value: '', textContent: '', disabled: false, selected: false, dataset: {} };
    }
  };
  return {
    options,
    ownerDocument,
    required: false,
    disabled: false,
    replaceChildren() { options.length = 0; },
    append(option) { options.push(option); },
    get value() { return options.find(option => option.selected)?.value ?? ''; }
  };
}

test('normalizes only active accounts from the bounded account page', () => {
  assert.deepEqual(normalizeActiveAccounts({ items: [
    { id: 3, name: '银行卡', archivedAt: null },
    { id: 2, name: '旧钱包', archivedAt: '2026-09-03T00:00:00Z' }
  ] }), [{ id: 3, name: '银行卡', archivedAt: null }]);
  assert.deepEqual(normalizeActiveAccounts(null), []);
});

test('account select is required and defaults to the first active account', () => {
  const select = selectHarness();

  const result = configureAccountSelect(select, [
    { id: 9, name: '工资卡' },
    { id: 4, name: '现金' }
  ], null, null);

  assert.equal(result.canSubmit, true);
  assert.equal(select.required, true);
  assert.equal(select.disabled, false);
  assert.equal(select.value, '9');
  assert.deepEqual(select.options.map(option => option.textContent), ['工资卡', '现金']);
});

test('account select preserves loaded page-two id 51 instead of defaulting to the first account', () => {
  const select = selectHarness();
  const accounts = Array.from({ length: 54 }, (_, index) => ({ id: index + 1, name: `账户${index + 1}` }));

  const result = configureAccountSelect(select, accounts, 51, '账户51');

  assert.equal(result.canSubmit, true);
  assert.equal(select.value, '51');
  assert.equal(select.options.find(option => option.selected).textContent, '账户51');
});

test('zero active accounts disable creation with an accessible explanation', () => {
  const select = selectHarness();

  const result = configureAccountSelect(select, [], null, null);

  assert.equal(result.canSubmit, false);
  assert.equal(result.message, '暂无可用账户，请先联系家庭管理员创建账户。');
  assert.equal(select.disabled, true);
  assert.equal(select.options[0].textContent, '暂无可用账户');
});

test('archived account remains visible during edit while payload preserves it unless replaced', () => {
  const select = selectHarness();
  const active = [{ id: 7, name: '新账户' }];

  const result = configureAccountSelect(select, active, 5, '历史账户');
  const preserved = transactionPayload(new Map([
    ['accountId', '5'], ['memberId', '1'], ['categoryId', '2'], ['amount', '8.80']
  ]), { activeAccounts: active, existingAccountId: 5 });

  assert.equal(result.canSubmit, true);
  assert.equal(select.options[0].textContent, '历史账户（已归档）');
  assert.equal(select.options[0].disabled, true);
  assert.equal(preserved.accountId, undefined);

  const replaced = transactionPayload(new Map([
    ['accountId', '7'], ['memberId', '1'], ['categoryId', '2'], ['amount', '8.80']
  ]), { activeAccounts: active, existingAccountId: 5 });
  assert.equal(replaced.accountId, 7);
});

test('paged-out category is omitted on edit to preserve it and page-two category 51 is submitted when loaded', () => {
  const accounts = [{ id: 5, name: '现金' }];
  const categories = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));
  const base = [
    ['accountId', '5'], ['memberId', '1'], ['kind', 'expense'], ['amount', '8.80']
  ];

  const preserved = transactionPayload(new Map([...base, ['categoryId', '99']]), {
    activeAccounts: accounts,
    availableCategories: categories,
    existingAccountId: 5,
    existingCategoryId: 99
  });
  assert.equal(preserved.categoryId, undefined);

  const selectedPageTwo = transactionPayload(new Map([...base, ['categoryId', '51']]), {
    activeAccounts: accounts,
    availableCategories: categories,
    existingAccountId: 5,
    existingCategoryId: 99
  });
  assert.equal(selectedPageTwo.categoryId, 51);
});

test('transaction page metadata is read from response headers with safe defaults', () => {
  const headers = new Map([
    ['X-Page', '2'], ['X-Page-Size', '20'], ['X-Total-Elements', '45'],
    ['X-Total-Pages', '3'], ['X-Has-Next', 'false']
  ]);
  assert.deepEqual(readTransactionPage({ get: name => headers.get(name) ?? null }), {
    page: 2,
    size: 20,
    totalElements: 45,
    totalPages: 3,
    hasNext: false
  });
});
