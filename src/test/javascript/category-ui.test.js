import test from 'node:test';
import assert from 'node:assert/strict';
import { availableCategoryParents, categoryPayload } from '../../main/resources/static/category-ui.js';

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
