# Stage 2 React and Feishu-Style Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the native SPA with the approved A1 Feishu-style React workspace covering registration, collaboration, ledger, budgets, assets, A-share investments, loans, reminders, and consolidated reporting.

**Architecture:** A Vite React/TypeScript app under `frontend/` uses Semi Design and TanStack Query. Maven downloads a pinned Node toolchain, runs frontend tests/build, and packages the generated assets into Spring Boot; API access is centralized so CSRF/session-expiry/request-id behavior remains identical to Stage 1.

**Tech Stack:** React 19.2.8, React DOM 19.2.8, TypeScript 7.0.2, Vite 8.2.2, `@vitejs/plugin-react` 6.1.1, Semi UI/Icons 2.103.0, TanStack Query 5.102.8, React Router 7.18.3, Vitest 4.1.11, React Testing Library 16.3.3, Playwright CLI, frontend-maven-plugin 2.0.2, Node 22.

**Spec:** `docs/superpowers/specs/2026-09-02-family-finance-stage-2-design.md`

## Global Constraints

- Plans 1–4 backend contracts are prerequisites and must not be guessed or duplicated in the client.
- No API token or financial record is persisted in localStorage/sessionStorage; only layout and table preferences may persist.
- Every write obtains/sends CSRF and central 401 handling performs the complete session-expiry transition exactly once.
- Semi Design is themed through semantic tokens; do not copy Feishu logos, illustrations, or protected brand assets.
- A1 layout keeps the 52px application rail, allows the 220px module sidebar to hide, and uses a mobile drawer at 390px.
- Desktop 1440×900 and mobile 390×844 must have no horizontal overflow, unnamed dialogs, duplicate accessible names, or uncaught console errors.
- The Windows startup command remains `start-local.cmd`; Maven supplies Node/npm locally.

---

### Task 1: Maven-integrated React/Vite/Semi foundation

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/package-lock.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/app/App.tsx`
- Create: `frontend/src/test/setup.ts`
- Modify: `pom.xml`
- Modify: `.gitignore`
- Create: `src/test/java/com/familyfinance/web/ReactDistributionTest.java`
- Delete after equivalent TypeScript tests pass: `src/main/resources/static/app.js`
- Delete after equivalent TypeScript tests pass: `src/main/resources/static/api-client.js`
- Delete after equivalent TypeScript tests pass: `src/main/resources/static/refresh-gate.js`
- Delete after equivalent TypeScript tests pass: `src/main/resources/static/session-expiry.js`
- Delete after React distribution is packaged: `src/main/resources/static/index.html`
- Delete after React theme is packaged: `src/main/resources/static/styles.css`
- Delete after equivalent TypeScript tests pass: `src/test/javascript/*.test.js`

**Interfaces:**
- Maven `generate-resources` installs Node 22 locally, runs `npm ci`, `npm test -- --run`, and `npm run build`.
- Vite writes to `${project.build.outputDirectory}/classes/static` or a staged directory copied there before packaging.

- [ ] **Step 1: Write failing packaged-distribution test**

```java
@Test
void reactDistributionProvidesHashedAssetsAndSpaEntry() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/assets/")));
    mvc.perform(get("/assets/app-missing.js"))
        .andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Create package manifest and failing React smoke test**

`package.json` pins the versions in this plan and scripts `dev`, `build`, `test`, `typecheck`. Create a Vitest test that renders `<App />` and expects the loading shell; run before App exists.

- [ ] **Step 3: Run RED**

```bash
cd frontend && npm ci && npm test -- --run
cd .. && ./mvnw -q -Dtest=ReactDistributionTest test
```

- [ ] **Step 4: Implement minimal React/Vite shell and Maven integration**

Configure frontend-maven-plugin executions for install-node-and-npm, `npm ci`, test, and build. Exclude Vite dev files from Spring static fallback; keep `/api/**` JSON 404 behavior.

Port the proven refresh generation, centralized 401 session-expiry, local-month, and API-client tests into TypeScript before deleting the Stage 1 JavaScript modules/tests listed above.

- [ ] **Step 5: Run GREEN and commit**

```bash
./mvnw clean test
./mvnw -DskipTests package
git add frontend pom.xml .gitignore src/test/java/com/familyfinance/web/ReactDistributionTest.java
git commit -m "build: add Maven-integrated React frontend"
```

---

### Task 2: Shared API client, auth, registration, and invite join

**Files:**
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/contracts.ts`
- Create: `frontend/src/auth/AuthProvider.tsx`
- Create: `frontend/src/auth/LoginPage.tsx`
- Create: `frontend/src/auth/RegisterPage.tsx`
- Create tests beside each module
- Modify: router/App

**Interfaces:**
- Central `api<T>(path, options) -> Promise<T>` handles JSON, request ID, CSRF, one-shot expiry, and field errors.
- Auth context exposes `{session, login, register, logout, changePassword}`.

- [ ] **Step 1: Write failing API/session tests**

Use MSW-free fetch stubs to verify one CSRF load shared across concurrent writes, 401 expiry once, request-id error display, and registration field mapping. Never assert source text.

- [ ] **Step 2: Implement typed API client**

Port the proven Stage 1 expiry behavior into TypeScript with AbortSignal support and no financial cache persistence.

- [ ] **Step 3: Write failing login/register/join component tests**

```tsx
it('submits normalized email create-family registration', async () => {
  render(<RegisterPage />);
  await user.type(screen.getByLabelText('邮箱'), ' Parent@Example.com ');
  await user.click(screen.getByRole('radio', {name:'创建新家庭'}));
  await user.type(screen.getByLabelText('家庭名称'), '凯文之家');
  await user.click(screen.getByRole('button', {name:'创建家庭'}));
  expect(register).toHaveBeenCalledWith(expect.objectContaining({email:'parent@example.com', mode:'CREATE'}));
});
```

Also cover invite token, password boundaries, server field errors, keyboard submit, and session expiry.

- [ ] **Step 4: Build Semi auth pages and route guards**

Use Semi Form/Input/Button/Tabs and plain product branding “家账”. No Feishu logo. Authenticated routes redirect to workspace; authenticated users cannot revisit login/register without logout.

- [ ] **Step 5: Run tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd .. && ./mvnw test
git add frontend/src/api frontend/src/auth frontend/src/app
git commit -m "feat: add React registration and authentication"
```

---

### Task 3: A1 workspace shell, permissions, and responsive navigation

**Files:**
- Create: `frontend/src/layout/AppRail.tsx`
- Create: `frontend/src/layout/ModuleSidebar.tsx`
- Create: `frontend/src/layout/MobileModuleDrawer.tsx`
- Create: `frontend/src/layout/WorkspaceHeader.tsx`
- Create: `frontend/src/layout/WorkspaceLayout.tsx`
- Create: `frontend/src/theme/semi-overrides.scss`
- Create layout/accessibility tests

**Interfaces:**
- Routes declare required role and rail/module identity.
- Sidebar collapsed preference key is `family-finance:module-sidebar-collapsed`; no other app data uses localStorage.

- [ ] **Step 1: Write failing role/navigation tests**

Render OWNER/ADMIN/MEMBER sessions and assert exact visible modules/actions. Member sees assets/investments/loans read-only pages but no admin mutation actions.

- [ ] **Step 2: Write failing collapse/mobile tests**

Assert 52px app rail remains after collapse, sidebar is removed from accessibility tree, preference restores after rerender, mobile drawer closes on route selection/Escape/outside click, and focus returns to trigger.

- [ ] **Step 3: Implement Semi token theme and shell**

Set primary/text/background/border/success/warning/danger tokens from the approved spec, 14px body, 4px spacing base, compact controls, subtle borders, and no large gradients/shadows.

- [ ] **Step 4: Run visual component tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd ..
git add frontend/src/layout frontend/src/theme frontend/src/app
git commit -m "feat: add collapsible Feishu-style workspace shell"
```

---

### Task 4: Ledger, budget, and recurring pages

**Files:**
- Create feature directories `frontend/src/features/ledger`, `budget`, `recurring`
- Create query hooks, pages, drawers/dialogs, tables/cards, tests

**Interfaces:**
- Uses Plan 2 accounts/categories/budget/recurring APIs; writes invalidate only affected ledger/budget/dashboard queries.

- [ ] **Step 1: Write failing query/form tests**

Cover account/category tree loading, transaction create/edit permissions, combined filters, budget revision form, recurring rule monthly day validation, occurrence confirm idempotent UI, and stale/field errors.

- [ ] **Step 2: Build desktop tables and mobile cards**

Use Semi Table with stable pagination and selected columns on desktop; use semantic list/cards on mobile. Forms use side sheets for long workflows and dialogs for confirmations.

- [ ] **Step 3: Add budget and recurring status views**

Budget bars pair color with labels and exact values; recurring pending items show due date, account/category, assignee, and confirm action. Do not compute spending or next due date in the browser.

- [ ] **Step 4: Run tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd .. && ./mvnw test
git add frontend/src/features/ledger frontend/src/features/budget frontend/src/features/recurring
git commit -m "feat: add React ledger budget and recurring views"
```

---

### Task 5: Asset, investment, and A-share market pages

**Files:**
- Create feature directories `asset`, `investment`, `market`
- Create asset table/detail/valuation drawer, investment trade/position views, quote status/refresh controls, tests

**Interfaces:**
- Uses Plan 3 APIs and preserves source/trade-date/fetched-at/stale status in every market price display.

- [ ] **Step 1: Write failing asset workflow tests**

Test type-specific fields, valuation history, admin-only mutation, archive, table-to-mobile transformation, and linked-loan state placeholders.

- [ ] **Step 2: Write failing investment/quote tests**

Test BUY/SELL/DIVIDEND/FEE forms, position values from server only, insufficient holdings errors, refresh disabled without Token, rate-limit status, stale quote, manual override, and screen-reader price freshness.

- [ ] **Step 3: Build approved A1 asset screen**

Match the confirmed mockup: collapsed module sidebar, app rail visible, summary strip, type filters, compact asset table, valuation/loan/profit columns, and responsive cards.

- [ ] **Step 4: Build investment and quote interactions**

Use table plus detail drawer; refresh button shows last run/result without polling. Manual price dialog is always available and clearly identifies source.

- [ ] **Step 5: Run tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd .. && ./mvnw test
git add frontend/src/features/asset frontend/src/features/investment frontend/src/features/market
git commit -m "feat: add React assets investments and A-share quotes"
```

---

### Task 6: Loan, notification, and family administration pages

**Files:**
- Create feature directories `loan`, `notification`, `family`
- Create loan wizard/schedule/prepayment/confirm pages, reminder center, membership/invite pages, tests

- [ ] **Step 1: Write failing loan UI tests**

Cover repayment method wizard, calculated schedule display from server, linked asset, confirm idempotency, partial/full prepay, disabled historical installments, and member permissions.

- [ ] **Step 2: Write failing notification/family tests**

Cover unread count, filter/read/resolve, navigation to source, invite plaintext shown once, revoke/copy, role changes, ownership transfer confirmation, and forbidden member actions.

- [ ] **Step 3: Implement accessible workflows**

Long loan creation uses Steps inside a side sheet; confirmation dialogs state monetary impact. Invite token uses explicit copy button and disappears after leaving success state.

Family settings also include password change and owner-only archive. Archive requires typing the exact family name, clearly states that logins will stop while historical data remains, and returns to the registration/login screen after success.

- [ ] **Step 4: Run tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd .. && ./mvnw test
git add frontend/src/features/loan frontend/src/features/notification frontend/src/features/family
git commit -m "feat: add React loans reminders and family roles"
```

---

### Task 7: Consolidated dashboard and analysis

**Files:**
- Create `frontend/src/features/dashboard` and reporting visual components/tests

**Interfaces:**
- Uses Plan 4 reporting endpoints; browser never recomputes net worth, debt ratio, budget usage, or portfolio P&L.

- [ ] **Step 1: Write failing dashboard state tests**

Cover loading/empty/error/stale quote/partial module failure states, month change, role-specific actions, and exact format of negative/large money.

- [ ] **Step 2: Build confirmed high-fidelity dashboard**

Implement greeting/title actions, four summary metrics, cashflow chart, asset allocation, reminders, quote freshness, and collapsible module sidebar. Use tested, accessible React SVG components; do not add another chart runtime in Stage 2.

- [ ] **Step 3: Build analysis pages**

Add net-worth history, budget execution, portfolio returns, loan progress, and member comparison with source freshness and zero/insufficient data descriptions.

- [ ] **Step 4: Run tests and commit**

```bash
cd frontend && npm test -- --run && npm run typecheck
cd .. && ./mvnw test
git add frontend/src/features/dashboard
git commit -m "feat: add Feishu-style family finance dashboard"
```

---

### Task 8: Cross-role E2E, migration, Windows, and packaging acceptance

**Files:**
- Create Playwright acceptance scripts/config under `frontend/e2e`
- Create `docs/acceptance/stage-2-frontend-checklist.md`
- Modify: `.github/workflows/windows-startup-smoke.yml`
- Modify: `README.md`

- [ ] **Step 1: Write failing owner/admin/member E2E flows**

Owner: register/create family/invite/role/complete finance workflow. Admin: manage ledger/assets/investments/loans but cannot transfer ownership. Member: view shared data, create/edit own transaction, confirm assigned item, forbidden admin actions.

- [ ] **Step 2: Add desktop/mobile visual and accessibility assertions**

Run 1440×900 and 390×844. Assert exact app rail/sidebar behavior, no horizontal overflow, visible focus, named dialogs/drawers, clean normal console, and expired-session write recovery.

- [ ] **Step 3: Verify real migration and Tushare-disabled operation**

Copy Stage 1 H2, run backup/migrations, open React UI, verify migrated demo data, restart, and repeat without `TUSHARE_TOKEN`. Use a local stub for quote-enabled flow.

- [ ] **Step 4: Update Windows workflow**

The real `start-local.cmd -NoBrowser -Smoke` must download the pinned frontend toolchain through Maven, build React, migrate, start, verify `/api/csrf` and React `/`, then stop the process tree.

- [ ] **Step 5: Run complete gate**

```bash
./mvnw clean verify
cd frontend && npm test -- --run && npm run typecheck && npm run build
cd ..
git diff --check
```

- [ ] **Step 6: Commit and review Plan 5**

```bash
git add frontend pom.xml .github/workflows/windows-startup-smoke.yml README.md docs/acceptance/stage-2-frontend-checklist.md
git commit -m "test: certify Stage 2 React workspace"
```
