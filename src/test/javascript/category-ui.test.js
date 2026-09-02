import test from 'node:test';
import assert from 'node:assert/strict';
import {
  availableCategoryParents,
  categoryPayload,
  configureCategorySelect
} from '../../main/resources/static/category-ui.js';

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

test('category payload preserves an existing child parent and can explicitly move it to root', () => {
  assert.deepEqual(categoryPayload([
    ['kind', 'expense'], ['name', '早餐'], ['color', '#123456'], ['parentId', '7']
  ]), { kind: 'expense', name: '早餐', color: '#123456', parentId: 7 });

  assert.deepEqual(categoryPayload([
    ['kind', 'expense'], ['name', '早餐'], ['color', '#123456'], ['parentId', '']
  ]), { kind: 'expense', name: '早餐', color: '#123456', parentId: null });
});

test('parent choices are same-kind roots and never include the category itself', () => {
  const categories = [
    { id: 1, kind: 'expense', level: 1, name: '餐饮' },
    { id: 2, kind: 'expense', level: 2, parentId: 1, name: '早餐' },
    { id: 3, kind: 'income', level: 1, name: '工资' },
    { id: 4, kind: 'expense', level: 1, name: '购物' }
  ];

  assert.deepEqual(availableCategoryParents(categories, 'expense', 1).map(category => category.id), [4]);
});

test('transaction category select preserves loaded page-two id 51', () => {
  const select = selectHarness();
  const categories = Array.from({ length: 54 }, (_, index) => ({
    id: index + 1,
    kind: 'expense',
    name: `分类${index + 1}`
  }));

  const result = configureCategorySelect(select, categories, 51, '分类51', 'expense');

  assert.equal(result.canSubmit, true);
  assert.equal(select.value, '51');
  assert.equal(select.options.find(option => option.selected).textContent, '分类51（支出）');
});

test('missing edit category is injected read-only when response metadata can preserve it', () => {
  const select = selectHarness();

  const result = configureCategorySelect(
    select,
    [{ id: 3, kind: 'expense', name: '餐饮' }],
    99,
    '历史分类',
    'expense'
  );

  assert.equal(result.canSubmit, true);
  assert.equal(select.value, '99');
  assert.equal(select.options[0].disabled, true);
  assert.equal(select.options[0].dataset.historical, 'true');
  assert.equal(select.options[0].textContent, '历史分类（未加载，保持不变）');
});

test('missing edit category without response metadata blocks save explicitly', () => {
  const select = selectHarness();

  const result = configureCategorySelect(select, [{ id: 3, kind: 'expense', name: '餐饮' }], 99, null, null);

  assert.equal(result.canSubmit, false);
  assert.match(result.message, /原分类未完整加载/);
  assert.equal(select.disabled, true);
});
