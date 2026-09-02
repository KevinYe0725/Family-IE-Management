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
  assert.throws(
    () => readBoundedPage(headers({
      'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '1',
      'X-Total-Pages': '0', 'X-Has-Next': 'false'
    }), '分类'),
    /分类分页响应异常/);
  assert.throws(
    () => readBoundedPage(headers({
      'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '54',
      'X-Total-Pages': '3', 'X-Has-Next': 'true'
    }), '分类'),
    /分类分页响应异常/);
});

test('rejects negative or fractional page, size, and total headers', () => {
  const valid = {
    'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '54',
    'X-Total-Pages': '2', 'X-Has-Next': 'true'
  };
  for (const [name, value] of [
    ['X-Page', '-1'], ['X-Page', '0.5'],
    ['X-Page-Size', '-1'], ['X-Page-Size', '1.5'],
    ['X-Total-Elements', '-1'], ['X-Total-Elements', '1.5'],
    ['X-Total-Pages', '-1'], ['X-Total-Pages', '1.5']
  ]) {
    assert.throws(
      () => readBoundedPage(headers({ ...valid, [name]: value }), '分类'),
      /分类分页响应异常/
    );
  }
});

test('surfaces the explicit max-page guard instead of looping or truncating', async () => {
  let calls = 0;

  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      maxPages: 3,
      pageSize: 1,
      fetchPage: async page => {
        calls += 1;
        return {
          data: [{ id: page + 1 }],
          headers: headers({
            'X-Page': String(page), 'X-Page-Size': '1', 'X-Total-Elements': '999',
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

test('accepts the canonical empty result shape without fabricating a page', async () => {
  const loaded = await loadAllBoundedPages({
    resourceName: '分类',
    fetchPage: async () => ({
      data: [],
      headers: headers({
        'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '0',
        'X-Total-Pages': '0', 'X-Has-Next': 'false'
      })
    }),
    itemsFrom: data => data
  });

  assert.deepEqual(loaded, []);
});

test('rejects an impossible zero-page result that contains an item', async () => {
  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async () => ({
        data: [{ id: 1 }],
        headers: headers({
          'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '0',
          'X-Total-Pages': '0', 'X-Has-Next': 'false'
        })
      }),
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );
});

for (const changedField of ['totalElements', 'totalPages', 'size']) {
  test(`rejects ${changedField} changes after capturing first-page metadata`, async () => {
    const resources = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));
    await assert.rejects(
      loadAllBoundedPages({
        resourceName: '分类',
        fetchPage: async (page, size) => {
          const response = responseFor(resources, page, size);
          if (page === 1) {
            const values = {
              'X-Page': '1', 'X-Page-Size': '50', 'X-Total-Elements': '54',
              'X-Total-Pages': '2', 'X-Has-Next': 'false'
            };
            if (changedField === 'totalElements') {
              values['X-Total-Elements'] = '55';
              response.data = [...response.data, { id: 55 }];
            }
            if (changedField === 'totalPages') values['X-Total-Pages'] = '3';
            if (changedField === 'size') {
              values['X-Page-Size'] = '27';
              response.data = resources.slice(27, 54);
            }
            response.headers = headers(values);
          }
          return response;
        },
        itemsFrom: data => data
      }),
      /分类分页响应异常/
    );
  });
}

test('uses a stable first-page effective size even when it is lower than requested', async () => {
  const resources = Array.from({ length: 44 }, (_, index) => ({ id: index + 1 }));

  const loaded = await loadAllBoundedPages({
    resourceName: '分类',
    fetchPage: async page => responseFor(resources, page, 40),
    itemsFrom: data => data
  });

  assert.equal(loaded.length, 44);
  assert.equal(loaded[40].id, 41);
});

test('rejects wrong nonlast and last page item counts', async () => {
  const resources = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));

  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async (page, size) => {
        const response = responseFor(resources, page, size);
        if (page === 0) response.data = response.data.slice(0, 49);
        return response;
      },
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );

  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async (page, size) => {
        const response = responseFor(resources, page, size);
        if (page === 1) response.data = response.data.slice(0, 3);
        return response;
      },
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );
});

test('rejects incorrect hasNext and response page metadata', async () => {
  const resources = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));
  const firstPage = responseFor(resources, 0, 50);
  firstPage.headers = headers({
    'X-Page': '0', 'X-Page-Size': '50', 'X-Total-Elements': '54',
    'X-Total-Pages': '2', 'X-Has-Next': 'false'
  });
  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async () => firstPage,
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );

  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async (page, size) => page === 0
        ? responseFor(resources, 0, size)
        : responseFor(resources, 0, size),
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );
});

test('rejects duplicate resource IDs within one page or across page boundaries', async () => {
  const resources = Array.from({ length: 54 }, (_, index) => ({ id: index + 1 }));
  const withinPage = [...resources];
  withinPage[1] = { id: 1 };
  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async (page, size) => responseFor(withinPage, page, size),
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );

  const acrossPages = [...resources];
  acrossPages[50] = { id: 50 };
  await assert.rejects(
    loadAllBoundedPages({
      resourceName: '分类',
      fetchPage: async (page, size) => responseFor(acrossPages, page, size),
      itemsFrom: data => data
    }),
    /分类分页响应异常/
  );
});

test('rejects resources without usable positive integer IDs', async () => {
  for (const invalidId of [undefined, null, '', 0, -1, 1.5]) {
    await assert.rejects(
      loadAllBoundedPages({
        resourceName: '账户',
        pageSize: 1,
        fetchPage: async () => ({
          data: { items: [{ id: invalidId }] },
          headers: headers({
            'X-Page': '0', 'X-Page-Size': '1', 'X-Total-Elements': '1',
            'X-Total-Pages': '1', 'X-Has-Next': 'false'
          })
        }),
        itemsFrom: data => data.items
      }),
      /账户分页响应异常/
    );
  }
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
