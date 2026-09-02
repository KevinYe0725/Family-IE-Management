import assert from 'node:assert/strict';
import test from 'node:test';
import { loadAllBoundedPages, readBoundedPage } from '../../main/resources/static/bounded-pages.js';

function headers(values) {
  return { get: name => values[name] ?? null };
}

function responseFor(resources, page, size, dataShape = items => items) {
  const start = page * size;
  const items = resources.slice(start, start + size);
  const totalPages = Math.ceil(resources.length / size);
  return {
    data: dataShape(items),
    headers: headers({
      'X-Page': String(page),
      'X-Page-Size': String(size),
      'X-Total-Elements': String(resources.length),
      'X-Total-Pages': String(totalPages),
      'X-Has-Next': String(page + 1 < totalPages)
    })
  };
}

test('loads 54 category pages sequentially with bounded requests and includes page-two id 51', async () => {
  const categories = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));
  const calls = [];

  const loaded = await loadAllBoundedPages({
    resourceName: '分类',
    fetchPage: async (page, size) => {
      calls.push({ page, size });
      return responseFor(categories, page, size);
    },
    itemsFrom: data => data
  });

  assert.deepEqual(calls, [{ page: 0, size: 50 }, { page: 1, size: 50 }]);
  assert.equal(loaded.length, 54);
  assert.equal(loaded[50].id, 51);
});

test('loads account page objects with the same bounded header traversal', async () => {
  const accounts = Array.from({ length: 54 }, (_, index) => ({ id: index + 1, archivedAt: null }));

  const loaded = await loadAllBoundedPages({
    resourceName: '账户',
    fetchPage: async (page, size) => responseFor(accounts, page, size, items => ({ items })),
    itemsFrom: data => data.items
  });

  assert.equal(loaded.length, 54);
  assert.equal(loaded.find(account => account.id === 51).id, 51);
});

test('reads required paging headers and rejects malformed values', () => {
  assert.deepEqual(readBoundedPage(headers({
    'X-Page': '1', 'X-Page-Size': '50', 'X-Total-Elements': '54',
    'X-Total-Pages': '2', 'X-Has-Next': 'false'
  }), '分类'), { page: 1, size: 50, totalElements: 54, totalPages: 2, hasNext: false });

  assert.throws(
    () => readBoundedPage(headers({ 'X-Page': '0', 'X-Has-Next': 'maybe' }), '分类'),
    /分类分页响应异常/);
});

test('surfaces the explicit max-page guard instead of looping or truncating', async () => {
  let calls = 0;

  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      maxPages: 3,
      fetchPage: async page => {
        calls += 1;
        return {
          data: [{ id: page + 1 }],
          headers: headers({
            'X-Page': String(page), 'X-Page-Size': '50', 'X-Total-Elements': '999',
            'X-Total-Pages': '999', 'X-Has-Next': 'true'
          })
        };
      },
      itemsFrom: data => data
    }),
    /分类数据超过安全页数上限（3 页）/
  );
  assert.equal(calls, 3);
});

test('rejects a repeated or out-of-order server page instead of silently duplicating data', async () => {
  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '账户',
      fetchPage: async page => ({
        data: { items: [{ id: page + 1 }] },
        headers: headers({
          'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '2',
          'X-Total-Pages': '2', 'X-Has-Next': 'true'
        })
      }),
      itemsFrom: data => data.items
    }),
    /账户分页响应异常/
  );
});
