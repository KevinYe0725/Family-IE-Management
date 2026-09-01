# Family Finance MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a locally runnable family finance web application with authentication, SQLite persistence, member/category/transaction management, filtering, dashboard statistics, rule-based analysis, CSV export, and a responsive browser UI.

**Architecture:** A zero-external-runtime-dependency Node.js 22 monolith serves both REST APIs and a static ES-module SPA. SQLite queries are always scoped by `household_id`; pure security, analytics, and UI state functions are isolated for unit testing, while HTTP behavior is exercised against a real ephemeral server and temporary database.

**Tech Stack:** Node.js 22 (`node:http`, `node:sqlite`, `node:crypto`, `node:test`), SQLite, semantic HTML, CSS, native browser ES modules, SVG.

**Spec:** `docs/superpowers/specs/2026-09-01-family-finance-mvp-design.md`

## Global Constraints

- Runtime floor is Node.js 22; production and tests must run without `npm install` or third-party packages.
- Start command is `npm start`; default bind is `127.0.0.1:4173`.
- Production database defaults to `data/family-finance.db`; tests use fresh temporary databases and remove them after each test.
- All money is stored as integer cents; public JSON transaction amounts are canonical strings with two decimals.
- Every member, category, transaction, dashboard, analysis, and export query is scoped by authenticated `household_id`.
- API success shape is `{ "data": ... }`; API error shape is `{ "error": { "code", "message", "fields"? } }`.
- Cookie session tokens are HttpOnly and SameSite=Lax; only a SHA-256 token hash is persisted.
- The seeded local demonstration account is `demo / demo1234`; the database stores only its scrypt hash.
- The browser must remain usable at 390 px and 1440 px widths, expose visible keyboard focus, and respect reduced motion.
- No budgets, assets, investments, OCR, external APIs, public registration, or complex roles in Sprint 1.

---

### Task 1: Runtime, security, schema, and deterministic demo seed

**Files:**
- Create: `package.json`
- Create: `src/config.js`
- Create: `src/security.js`
- Create: `src/database.js`
- Create: `tests/helpers.js`
- Test: `tests/security.test.js`
- Test: `tests/database.test.js`

**Interfaces:**
- Produces: `loadConfig(env) -> {host, port, dbPath, secureCookies}`.
- Produces: `hashPassword(password) -> encoded`, `verifyPassword(password, encoded) -> boolean`, `newSessionToken() -> string`, `hashSessionToken(token) -> hex`.
- Produces: `openDatabase(path) -> DatabaseSync`, `migrate(db)`, `seedDemo(db)`, `createStore(db) -> store`, `closeDatabase(db)`.
- `store` initially exposes `findUserByUsername`, `createSession`, `findSession`, `deleteSession`, `listMembers`, and `listCategories`; later tasks extend the same object.

- [ ] **Step 1: Write failing security tests**

```js
// tests/security.test.js
import test from 'node:test';
import assert from 'node:assert/strict';
import { hashPassword, verifyPassword, newSessionToken, hashSessionToken } from '../src/security.js';

test('password hash verifies only the original password', () => {
  const encoded = hashPassword('demo1234');
  assert.equal(encoded.includes('demo1234'), false);
  assert.equal(verifyPassword('demo1234', encoded), true);
  assert.equal(verifyPassword('wrong-password', encoded), false);
});

test('session storage uses a one-way token hash', () => {
  const token = newSessionToken();
  assert.match(token, /^[A-Za-z0-9_-]{40,}$/);
  assert.match(hashSessionToken(token), /^[a-f0-9]{64}$/);
  assert.equal(hashSessionToken(token).includes(token), false);
});
```

- [ ] **Step 2: Run the security test and verify RED**

Run: `node --no-warnings --test tests/security.test.js`

Expected: FAIL with `ERR_MODULE_NOT_FOUND` for `src/security.js`.

- [ ] **Step 3: Implement the minimal security module and package scripts**

Use `scryptSync` with a random 16-byte salt and `timingSafeEqual`. Encode hashes as `scrypt$<salt-hex>$<hash-hex>`. Generate session tokens with `randomBytes(32).toString('base64url')` and hash them with SHA-256.

```json
{
  "name": "family-finance-mvp",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": ">=22" },
  "scripts": {
    "start": "node --no-warnings src/server.js",
    "test": "node --no-warnings --test --test-concurrency=1"
  }
}
```

- [ ] **Step 4: Run the security test and verify GREEN**

Run: `node --no-warnings --test tests/security.test.js`

Expected: 2 tests pass, 0 fail.

- [ ] **Step 5: Write failing schema and seed tests**

```js
// tests/database.test.js
test('migration and demo seed are idempotent and store no plaintext password', () => {
  const { db, cleanup } = createTestDatabase();
  migrate(db); seedDemo(db); seedDemo(db);
  const user = db.prepare('select username, password_hash from users').get();
  assert.equal(user.username, 'demo');
  assert.equal(user.password_hash.includes('demo1234'), false);
  assert.equal(db.prepare('select count(*) as count from households').get().count, 1);
  assert.ok(db.prepare('select count(*) as count from transactions').get().count >= 12);
  cleanup();
});

test('store lists only reference data in the requested household', () => {
  const { db, cleanup } = createSeededTestDatabase();
  const store = createStore(db);
  const demo = store.findUserByUsername('demo');
  assert.ok(store.listMembers(demo.householdId).every((row) => row.householdId === demo.householdId));
  assert.ok(store.listCategories(demo.householdId).every((row) => row.householdId === demo.householdId));
  cleanup();
});
```

`tests/helpers.js` must create a unique directory with `mkdtempSync(join(tmpdir(), 'family-finance-'))`, open `<dir>/test.db`, and expose a cleanup that closes the database before `rmSync(dir, {recursive:true, force:true})`.

- [ ] **Step 6: Run database tests and verify RED**

Run: `node --no-warnings --test tests/database.test.js`

Expected: FAIL because `src/database.js` and test helpers do not exist.

- [ ] **Step 7: Implement config, schema, store, and deterministic seed**

Enable `PRAGMA foreign_keys = ON` and `PRAGMA journal_mode = WAL`. Create the six tables and indexes from the spec. `seedDemo` must run in a transaction, check for username `demo`, and insert one household, five named family members, default income/expense categories, and deterministic June–September 2026 transactions. Use fixed dates and amounts so later dashboard tests have literal expected totals.

- [ ] **Step 8: Run Task 1 tests and verify GREEN**

Run: `node --no-warnings --test tests/security.test.js tests/database.test.js`

Expected: all tests pass with no warnings.

- [ ] **Step 9: Commit Task 1**

```bash
git add package.json src/config.js src/security.js src/database.js tests/helpers.js tests/security.test.js tests/database.test.js
git commit -m "feat: add secure SQLite foundation"
```

---

### Task 2: HTTP shell, authentication, members, and categories

**Files:**
- Create: `src/http.js`
- Create: `src/app.js`
- Create: `src/server.js`
- Modify: `src/database.js`
- Modify: `tests/helpers.js`
- Test: `tests/api-auth.test.js`
- Test: `tests/api-reference.test.js`

**Interfaces:**
- Consumes: Task 1 security and store functions.
- Produces: `createApp({db, staticDir, logger, secureCookies, now}) -> async (req, res) => void`.
- Produces test helper `startTestServer() -> {baseUrl, db, request, close}` where `request(path, options)` retains the session cookie.
- Extends store with `createMember`, `updateMember`, `deleteMember`, `createCategory`, `updateCategory`, `deleteCategory`.

- [ ] **Step 1: Write failing authentication API tests**

```js
test('protected API rejects anonymous requests with the standard error shape', async () => {
  const app = await startTestServer();
  const response = await fetch(`${app.baseUrl}/api/members`);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), {
    error: { code: 'AUTH_REQUIRED', message: '请先登录' }
  });
  await app.close();
});

test('demo login establishes and logout clears an HttpOnly session', async () => {
  const app = await startTestServer();
  const login = await app.request('/api/auth/login', {
    method: 'POST', body: { username: 'demo', password: 'demo1234' }
  });
  assert.equal(login.status, 200);
  assert.match(login.headers.get('set-cookie'), /HttpOnly; SameSite=Lax/);
  assert.equal((await app.request('/api/session')).status, 200);
  assert.equal((await app.request('/api/auth/logout', {method: 'POST'})).status, 204);
  assert.equal((await app.request('/api/session')).status, 401);
  await app.close();
});
```

- [ ] **Step 2: Run auth tests and verify RED**

Run: `node --no-warnings --test tests/api-auth.test.js`

Expected: FAIL because the HTTP application does not exist.

- [ ] **Step 3: Implement HTTP helpers, auth routes, and server entry point**

`src/http.js` must parse JSON with a 1 MiB maximum, parse cookies, write JSON/errors, and assign a request ID. `src/app.js` authenticates by hashing the Cookie token and loading an unexpired session. `src/server.js` opens, migrates, and seeds the configured database before listening, logs the local URL, and closes on SIGINT/SIGTERM.

- [ ] **Step 4: Run auth tests and verify GREEN**

Run: `node --no-warnings --test tests/api-auth.test.js`

Expected: all auth tests pass.

- [ ] **Step 5: Write failing members and categories API tests**

Cover these literal behaviors with real HTTP calls after login:

```js
test('member CRUD stays inside the authenticated household', async () => {
  const app = await loggedInTestServer();
  const created = await app.request('/api/members', {method:'POST', body:{name:'奶奶', roleLabel:'长辈'}});
  assert.equal(created.status, 201);
  const member = (await created.json()).data;
  assert.equal(member.name, '奶奶');
  assert.equal((await app.request(`/api/members/${member.id}`, {method:'PATCH', body:{name:'外婆', roleLabel:'长辈'}})).status, 200);
  assert.equal((await app.request(`/api/members/${member.id}`, {method:'DELETE'})).status, 204);
  await app.close();
});

test('category validation rejects an unsupported kind', async () => {
  const app = await loggedInTestServer();
  const response = await app.request('/api/categories', {method:'POST', body:{kind:'transfer', name:'转账'}});
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error.fields.kind, '类型只能是收入或支出');
  await app.close();
});
```

Also create referenced member/category records and assert deletion returns `409` with code `RESOURCE_IN_USE`.

- [ ] **Step 6: Run reference-data tests and verify RED**

Run: `node --no-warnings --test tests/api-reference.test.js`

Expected: FAIL with 404 for missing routes.

- [ ] **Step 7: Implement members/categories store methods and routes**

Trim names, require 1–30 characters, require category `kind` to be `income` or `expense`, validate colors as `#[0-9A-Fa-f]{6}`, and return rows as camelCase. Every `UPDATE`, `DELETE`, and reference lookup includes both `id` and `household_id`.

- [ ] **Step 8: Run Task 2 tests and verify GREEN**

Run: `node --no-warnings --test tests/api-auth.test.js tests/api-reference.test.js`

Expected: all tests pass.

- [ ] **Step 9: Commit Task 2**

```bash
git add src/http.js src/app.js src/server.js src/database.js tests/helpers.js tests/api-auth.test.js tests/api-reference.test.js
git commit -m "feat: add authentication and household reference APIs"
```

---

### Task 3: Transaction CRUD, filters, and CSV export

**Files:**
- Modify: `src/database.js`
- Modify: `src/app.js`
- Test: `tests/api-transactions.test.js`
- Test: `tests/api-export.test.js`

**Interfaces:**
- Extends store with `listTransactions`, `getTransaction`, `createTransaction`, `updateTransaction`, `deleteTransaction`.
- Filter shape: `{month, from, to, kind, memberId, categoryId, q}`.
- JSON transaction shape: `{id, kind, amount, occurredOn, member:{id,name}, category:{id,name,color}, merchant, location, note}`.

- [ ] **Step 1: Write failing transaction behavior tests**

```js
test('creates a decimal amount as integer cents and returns canonical money', async () => {
  const app = await loggedInTestServer();
  const refs = await app.referenceData();
  const response = await app.request('/api/transactions', {
    method:'POST',
    body:{kind:'expense', amount:'12.30', occurredOn:'2026-09-01', memberId:refs.memberId,
          categoryId:refs.expenseCategoryId, merchant:'菜场', location:'城西', note:'晚餐食材'}
  });
  assert.equal(response.status, 201);
  assert.equal((await response.json()).data.amount, '12.30');
  assert.equal(app.db.prepare('select amount_cents from transactions where merchant=?').get('菜场').amount_cents, 1230);
  await app.close();
});

test('rejects a category whose kind does not match the transaction', async () => {
  const app = await loggedInTestServer();
  const refs = await app.referenceData();
  const response = await app.request('/api/transactions', {
    method:'POST', body:{kind:'expense', amount:'10.00', occurredOn:'2026-09-01',
      memberId:refs.memberId, categoryId:refs.incomeCategoryId}
  });
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error.fields.categoryId, '分类与收支类型不一致');
  await app.close();
});
```

Add tests for edit, delete, 404 outside household, and combined filters `month=2026-09&kind=expense&q=餐` returning only literal expected seed rows.

- [ ] **Step 2: Run transaction tests and verify RED**

Run: `node --no-warnings --test tests/api-transactions.test.js`

Expected: FAIL with 404 for transaction routes.

- [ ] **Step 3: Implement money/date validation, store queries, and routes**

Parse money with `/^\d{1,9}(?:\.\d{1,2})?$/`, convert by string manipulation, require amount cents > 0, validate calendar dates by round-tripping UTC components, and cap free-text fields at 100/100/500 characters. Build SQL filters from an allowlisted array of clauses and bound parameters; never interpolate user values.

- [ ] **Step 4: Run transaction tests and verify GREEN**

Run: `node --no-warnings --test tests/api-transactions.test.js`

Expected: all transaction tests pass.

- [ ] **Step 5: Write failing CSV export test**

```js
test('CSV export follows filters and preserves Chinese in UTF-8', async () => {
  const app = await loggedInTestServer();
  const response = await app.request('/api/export.csv?month=2026-09&kind=expense');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/csv; charset=utf-8/);
  const csv = await response.text();
  assert.ok(csv.startsWith('\uFEFF日期,类型,金额,成员,分类,商家,地点,备注\r\n'));
  assert.match(csv, /支出/);
  assert.doesNotMatch(csv, /工资/);
  await app.close();
});
```

- [ ] **Step 6: Run export test and verify RED**

Run: `node --no-warnings --test tests/api-export.test.js`

Expected: FAIL with 404 for `/api/export.csv`.

- [ ] **Step 7: Implement safe CSV export**

Reuse `listTransactions` filters. Escape fields containing quote/comma/newline by doubling quotes and surrounding the field with quotes. Prefix UTF-8 BOM and return `Content-Disposition: attachment; filename="family-finance.csv"`.

- [ ] **Step 8: Run Task 3 tests and verify GREEN**

Run: `node --no-warnings --test tests/api-transactions.test.js tests/api-export.test.js`

Expected: all tests pass.

- [ ] **Step 9: Commit Task 3**

```bash
git add src/database.js src/app.js tests/api-transactions.test.js tests/api-export.test.js
git commit -m "feat: add transaction workflows and CSV export"
```

---

### Task 4: Dashboard aggregation and rule-based analysis

**Files:**
- Create: `src/analytics.js`
- Modify: `src/database.js`
- Modify: `src/app.js`
- Test: `tests/analytics.test.js`
- Test: `tests/api-dashboard.test.js`

**Interfaces:**
- Produces: `buildDashboard({month, transactions, members, categories}) -> dashboard`.
- Produces: `buildAnalysis({month, currentTransactions, historicalMonthlyExpenses}) -> {insights, historyStatus}`.
- Adds `GET /api/dashboard?month=YYYY-MM` and `GET /api/analysis?month=YYYY-MM`.

- [ ] **Step 1: Write failing pure analytics tests with hand-calculated literals**

```js
test('dashboard totals, daily trend, category shares, and member totals reconcile', () => {
  const dashboard = buildDashboard({
    month:'2026-09',
    transactions:[
      tx('income', 500000, '2026-09-01', 1, 10),
      tx('expense', 12000, '2026-09-02', 1, 20),
      tx('expense', 8000, '2026-09-02', 2, 21)
    ],
    members:[{id:1,name:'叶凯文'},{id:2,name:'周崇浩'}],
    categories:[{id:20,name:'餐饮',color:'#D8664B'},{id:21,name:'交通',color:'#3B7A72'}]
  });
  assert.deepEqual(dashboard.summary, {income:'5000.00', expense:'200.00', balance:'4800.00'});
  assert.deepEqual(dashboard.daily, [
    {date:'2026-09-01', income:'5000.00', expense:'0.00'},
    {date:'2026-09-02', income:'0.00', expense:'200.00'}
  ]);
  assert.equal(dashboard.expenseByCategory[0].share, 60);
});

test('analysis reports a 50 percent increase over three-month average', () => {
  const result = buildAnalysis({
    month:'2026-09',
    currentTransactions:[tx('expense', 150000, '2026-09-01', 1, 20)],
    historicalMonthlyExpenses:[100000, 90000, 110000]
  });
  assert.equal(result.insights[0].code, 'MONTHLY_INCREASE');
  assert.equal(result.insights[0].metric, '50.0%');
});
```

- [ ] **Step 2: Run pure analytics tests and verify RED**

Run: `node --no-warnings --test tests/analytics.test.js`

Expected: FAIL because `src/analytics.js` is missing.

- [ ] **Step 3: Implement pure dashboard and analysis functions**

Sort trend dates ascending, category/member totals descending, and round shares to one decimal. Analysis must emit at most three ordered insights: monthly comparison, top category, and largest single expense. If fewer than two historical months exist, set `historyStatus:'insufficient'` and omit the comparison insight.

- [ ] **Step 4: Run pure analytics tests and verify GREEN**

Run: `node --no-warnings --test tests/analytics.test.js`

Expected: all pure analytics tests pass.

- [ ] **Step 5: Write failing dashboard API tests**

Login, request September 2026, and assert API summary values equal a direct literal sum of the deterministic seed fixture. Assert invalid `month=2026-13` returns `422`, and an empty month returns zero totals with `historyStatus` rather than a fabricated increase.

- [ ] **Step 6: Run dashboard API tests and verify RED**

Run: `node --no-warnings --test tests/api-dashboard.test.js`

Expected: FAIL with 404 for dashboard and analysis routes.

- [ ] **Step 7: Implement aggregate store reads and API routes**

The route loads only the authenticated household's rows, passes them into pure functions, and returns `{data: dashboard}` or `{data: analysis}`. Do not duplicate aggregation logic inside HTTP handlers.

- [ ] **Step 8: Run Task 4 tests and verify GREEN**

Run: `node --no-warnings --test tests/analytics.test.js tests/api-dashboard.test.js`

Expected: all tests pass.

- [ ] **Step 9: Commit Task 4**

```bash
git add src/analytics.js src/database.js src/app.js tests/analytics.test.js tests/api-dashboard.test.js
git commit -m "feat: add dashboard statistics and financial analysis"
```

---

### Task 5: Responsive SPA and real browser workflow

**Files:**
- Create: `public/index.html`
- Create: `public/styles.css`
- Create: `public/ui-state.js`
- Create: `public/app.js`
- Modify: `src/app.js`
- Test: `tests/ui-state.test.js`
- Test: `tests/static-app.test.js`

**Interfaces:**
- Consumes all Task 2–4 APIs.
- Produces pure browser helpers `formatMoney`, `buildTransactionQuery`, `buildDailyPath`, and `formToTransactionPayload` from `public/ui-state.js`.
- Produces views identified by `[data-view="dashboard"]`, `[data-view="transactions"]`, `[data-view="analysis"]`, and `[data-view="settings"]`.

- [ ] **Step 1: Write failing UI-state tests**

```js
test('buildTransactionQuery omits empty filters and encodes Chinese search', () => {
  assert.equal(buildTransactionQuery({month:'2026-09', kind:'expense', q:'晚餐', memberId:''}),
    '?month=2026-09&kind=expense&q=%E6%99%9A%E9%A4%90');
});

test('form payload normalizes optional text without changing decimal amount', () => {
  assert.deepEqual(formToTransactionPayload({
    kind:'expense', amount:'12.30', occurredOn:'2026-09-01', memberId:'2', categoryId:'5',
    merchant:'  菜场 ', location:' ', note:' 晚餐 '
  }), {kind:'expense', amount:'12.30', occurredOn:'2026-09-01', memberId:2, categoryId:5,
       merchant:'菜场', location:'', note:'晚餐'});
});
```

Add a `buildDailyPath` test with literal SVG path output for three daily points and a `formatMoney('1234.50') === '¥1,234.50'` test.

- [ ] **Step 2: Run UI-state tests and verify RED**

Run: `node --no-warnings --test tests/ui-state.test.js`

Expected: FAIL because `public/ui-state.js` is missing.

- [ ] **Step 3: Implement pure UI-state helpers**

Keep the module browser-compatible and free of DOM globals so Node can import it. Encode query keys in stable order: month, from, to, kind, memberId, categoryId, q.

- [ ] **Step 4: Run UI-state tests and verify GREEN**

Run: `node --no-warnings --test tests/ui-state.test.js`

Expected: all helper tests pass.

- [ ] **Step 5: Write failing static/runtime shell test**

Start the real test server and assert `GET /` returns HTML, `GET /styles.css` returns CSS, and `GET /app.js` returns JavaScript. Then assert a path such as `/transactions` returns the SPA HTML while `/api/unknown` remains JSON 404. These are application boundary behaviors, not source-text assertions.

- [ ] **Step 6: Run static app test and verify RED**

Run: `node --no-warnings --test tests/static-app.test.js`

Expected: FAIL with 404 for `/`.

- [ ] **Step 7: Build the semantic HTML and visual system**

Use the exact palette and typography from the spec. Create a login panel, app shell, ledger-style navigation, month picker, summary strip, cashflow SVG, category/member bars, analysis insight list, transaction filter/list, transaction modal form, and settings panels for members/categories. Include a persistent status region with `role="status"`, modal focus management, visible labels, and explicit empty/error messages.

- [ ] **Step 8: Implement browser state and API workflows**

`public/app.js` must handle session boot, login/logout, navigation, loading states, month changes, transaction CRUD, filters, CSV download, member/category CRUD, and modal close on Escape. After any write, refresh the current view from the API rather than mutating guessed totals locally.

- [ ] **Step 9: Implement safe static serving and SPA fallback**

Serve only files resolved inside `public/`, set content types, use `Cache-Control: no-store` for HTML and API, and return `index.html` only for extensionless non-API GET paths. Block `..` traversal.

- [ ] **Step 10: Run Task 5 tests and verify GREEN**

Run: `node --no-warnings --test tests/ui-state.test.js tests/static-app.test.js`

Expected: all tests pass.

- [ ] **Step 11: Commit Task 5**

```bash
git add public src/app.js tests/ui-state.test.js tests/static-app.test.js
git commit -m "feat: add responsive household ledger interface"
```

---

### Task 6: Full-system acceptance, operations, and handoff

**Files:**
- Create: `tests/smoke.test.js`
- Create: `README.md`
- Create: `docs/acceptance/sprint-1-checklist.md`
- Modify: `package.json`

**Interfaces:**
- Consumes the complete server and SPA.
- Produces `npm run smoke`, which runs a real HTTP workflow against a temporary database.

- [ ] **Step 1: Write failing smoke test**

The test must execute this real sequence against a fresh server:

1. Login as demo.
2. Read members and categories.
3. Create a `¥88.60` expense on `2026-09-10` with Chinese merchant/location/note.
4. Fetch it through combined month/kind/keyword filters.
5. Verify dashboard expense increases by exactly `88.60`.
6. Verify analysis returns an insight array.
7. Export CSV and find the created row.
8. Close and reopen the same database, login again, and verify the row persisted.
9. Delete the row and verify it is absent.

- [ ] **Step 2: Run smoke test and verify RED**

Run: `node --no-warnings --test tests/smoke.test.js`

Expected: FAIL on the first incomplete integration behavior, with the failure naming the broken workflow.

- [ ] **Step 3: Make only the minimal integration corrections required for GREEN**

Do not add scope. Fix contract mismatches, restart behavior, or response handling exposed by the smoke test, and add a regression assertion for each correction inside the same smoke test or the owning focused test.

- [ ] **Step 4: Run the complete automated suite**

Run: `npm test`

Expected: all tests pass, 0 fail, no warning output.

- [ ] **Step 5: Write the operator README and acceptance checklist**

README must include prerequisites, `npm start`, local URL, demo account, data path, test commands, reset instructions that move the database aside rather than deleting silently, API overview, project structure, and Sprint 1 exclusions. The checklist must map each spec acceptance criterion to an automated command or browser action.

- [ ] **Step 6: Run real browser acceptance at desktop and mobile widths**

Use Playwright against a running `npm start` process. Verify login, dashboard, create/edit/filter/delete transaction, analysis, member/category settings, CSV request, logout, keyboard focus, no horizontal overflow at 1440×900 and 390×844, and no uncaught console errors. Capture screenshots only as QA evidence, not deliverables.

- [ ] **Step 7: Run persistence restart acceptance**

Create a uniquely named transaction through the browser, stop and restart the server using the same production database, and verify the transaction remains. Remove only the test transaction afterward through the UI.

- [ ] **Step 8: Commit Task 6**

```bash
git add package.json tests/smoke.test.js README.md docs/acceptance/sprint-1-checklist.md
git commit -m "test: certify Sprint 1 family finance workflow"
```

