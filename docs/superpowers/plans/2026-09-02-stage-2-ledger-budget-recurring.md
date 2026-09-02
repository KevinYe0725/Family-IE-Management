# Stage 2 Ledger, Budget, and Recurring Billing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the household ledger with financial accounts, two-level categories, budgets with history, recurring rules, pending occurrences, and idempotent confirmation into real transactions.

**Architecture:** Ledger entities remain household-scoped JPA aggregates. Derived budget usage is queried from confirmed transactions rather than stored; scheduled recurring rules create unique pending occurrences, and confirmation atomically creates one transaction plus resolves the occurrence.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring Data JPA, Flyway, H2, Spring Scheduling, JUnit 5, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-02-family-finance-stage-2-design.md`

## Global Constraints

- Plan 1 membership and permission services are prerequisites.
- Existing transactions receive an account and creator during V3 migration without changing amount/date/category/member data.
- Categories have at most two levels and parent/child kinds always match.
- Budget usage is computed from confirmed expense transactions in the requested month.
- Scheduled generation and confirmation are idempotent through database unique constraints and row locking.
- Members may mutate only transactions they created; owners/admins may manage all ledger configuration.

## Plan API Contracts

- `GET|POST /api/accounts`
- `PATCH|DELETE /api/accounts/{id}`
- Existing category/transaction APIs extended with `parentId` and `accountId`.
- `GET|POST /api/budgets`
- `PATCH /api/budgets/{id}`
- `GET|POST /api/recurring-rules`
- `PATCH|DELETE /api/recurring-rules/{id}`
- `GET /api/recurring-occurrences`
- `POST /api/recurring-occurrences/{id}/confirm`

---

### Task 1: V3 account, category, budget, and recurring migration

**Files:**
- Create: `src/main/resources/db/migration/V3__accounts_categories_recurring_budgets.sql`
- Modify: `src/main/java/com/familyfinance/transaction/FinancialTransaction.java`
- Modify: `src/main/java/com/familyfinance/category/Category.java`
- Create: `src/test/java/com/familyfinance/migration/LedgerStageTwoMigrationTest.java`

**Interfaces:**
- Produces account/category/budget/recurring tables and links every existing transaction to one default account and the migrated demo owner.

- [ ] **Step 1: Write failing migration preservation test**

```java
@Test
void v3BackfillsAccountCreatorAndTopLevelCategories() {
    Path db = migrateStageOneFixtureThroughV3();
    assertThat(queryLong(db, "select count(*) from financial_accounts where name='默认账户'")).isEqualTo(1);
    assertThat(queryLong(db, "select count(*) from financial_transactions where account_id is null")).isZero();
    assertThat(queryLong(db, "select count(*) from financial_transactions where created_by_user_id is null")).isZero();
    assertThat(queryLong(db, "select count(*) from categories where parent_id is not null")).isZero();
    assertThat(queryLong(db, "select count(*) from financial_transactions")).isEqualTo(12);
}
```

- [ ] **Step 2: Run migration test and verify RED**

Run: `./mvnw -q -Dtest=LedgerStageTwoMigrationTest test`

Expected: FAIL because V3 does not exist.

- [ ] **Step 3: Implement V3 SQL**

Create `financial_accounts`, add `categories.parent_id`, add account/creator/source columns to transactions, create `budgets`, `budget_revisions`, `recurring_rules`, and `recurring_occurrences`. Insert one default account per household, backfill existing rows, then make account/creator non-null.

- [ ] **Step 4: Update entities and run tests**

Map account and creator as lazy associations. Add the category self-reference with a check in the constructor/update method that rejects a third level or mismatched kind.

```bash
./mvnw -q -Dtest=LedgerStageTwoMigrationTest test
./mvnw test
```

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/resources/db/migration/V3__accounts_categories_recurring_budgets.sql src/main/java/com/familyfinance/transaction src/main/java/com/familyfinance/category src/test/java/com/familyfinance/migration
git commit -m "feat: migrate ledger accounts budgets and recurring rules"
```

---

### Task 2: Financial account management and transaction integration

**Files:**
- Create: `src/main/java/com/familyfinance/ledger/FinancialAccount.java`
- Create: `src/main/java/com/familyfinance/ledger/AccountType.java`
- Create: `src/main/java/com/familyfinance/ledger/FinancialAccountRepository.java`
- Create: `src/main/java/com/familyfinance/ledger/AccountService.java`
- Create: `src/main/java/com/familyfinance/ledger/AccountController.java`
- Create account DTO records
- Modify: transaction request/response/service/filter files
- Modify: `src/main/java/com/familyfinance/identity/RegistrationService.java`
- Test: `src/test/java/com/familyfinance/ledger/AccountApiTest.java`
- Test: `src/test/java/com/familyfinance/transaction/TransactionAccountApiTest.java`

**Interfaces:**
- Produces account CRUD and requires `accountId` for new transactions.
- Registration CREATE now creates the default account in the same transaction.

- [ ] **Step 1: Write failing account API tests**

```java
@Test
void adminCreatesAccountAndMemberCannotArchiveIt() throws Exception {
    long accountId = createAccount(adminSession, "家庭银行卡", "BANK", "CNY", "1000.00");
    getAccount(adminSession, accountId).andExpect(jsonPath("$.data.openingBalance").value("1000.00"));
    deleteAccount(memberSession, accountId).andExpect(status().isForbidden());
}
```

Cover CASH/BANK/WALLET types, name uniqueness, archive conflict when active recurring rules reference the account, cross-household 404, and owner/admin permissions.

- [ ] **Step 2: Run account tests and verify RED**

Run: `./mvnw -q -Dtest=AccountApiTest test`

Expected: 404 for account routes.

- [ ] **Step 3: Implement accounts and permissions**

Use integer cents for opening balance, currency fixed to CNY in Stage 2, and archive instead of physical delete when historical transactions exist.

- [ ] **Step 4: Write failing transaction-account tests**

Assert create rejects missing/cross-household account, response includes account, filters support `accountId`, and existing member/owner permission rules still apply.

- [ ] **Step 5: Implement transaction integration and registration default**

Extend DTOs/filter/specification. `RegistrationService` calls an account factory after household creation. Do not allow clients to change the transaction creator.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw -q -Dtest=AccountApiTest,TransactionAccountApiTest test
./mvnw test
git add src/main/java/com/familyfinance/ledger src/main/java/com/familyfinance/transaction src/main/java/com/familyfinance/identity src/test/java/com/familyfinance/ledger src/test/java/com/familyfinance/transaction
git commit -m "feat: add financial accounts to the ledger"
```

---

### Task 3: Two-level category hierarchy

**Files:**
- Modify category entity/repository/service/controller and DTOs
- Modify transaction filtering responses
- Test: `src/test/java/com/familyfinance/category/CategoryHierarchyApiTest.java`

**Interfaces:**
- Category responses include `parentId`, `level`, and children; transactions may select either level but reporting rolls child totals into parent summaries when requested.

- [ ] **Step 1: Write failing hierarchy tests**

```java
@Test
void createsOneChildButRejectsGrandchildAndKindMismatch() throws Exception {
    long shopping = createCategory("expense", "购物", null);
    long clothing = createCategory("expense", "服饰", shopping);
    createCategory("expense", "外套", clothing).andExpect(status().isUnprocessableEntity());
    createCategory("income", "错误收入", shopping).andExpect(status().isUnprocessableEntity());
}
```

Also test parent deletion with children/transactions, move between compatible parents, stable tree ordering, and household isolation.

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -q -Dtest=CategoryHierarchyApiTest test`

- [ ] **Step 3: Implement hierarchy and reporting projection**

Use explicit service validation rather than recursive JPA serialization. Return flat rows plus a deterministic tree DTO; do not expose entity children directly.

- [ ] **Step 4: Run tests and commit**

```bash
./mvnw -q -Dtest=CategoryHierarchyApiTest,CategoryApiTest test
./mvnw test
git add src/main/java/com/familyfinance/category src/main/java/com/familyfinance/transaction src/test/java/com/familyfinance/category
git commit -m "feat: add hierarchical household categories"
```

---

### Task 4: Budgets and immutable revision history

**Files:**
- Create: `src/main/java/com/familyfinance/budget/Budget.java`
- Create: `src/main/java/com/familyfinance/budget/BudgetRevision.java`
- Create repositories, service, controller, DTOs
- Create: `src/test/java/com/familyfinance/budget/BudgetApiTest.java`
- Create: `src/test/java/com/familyfinance/budget/BudgetUsageServiceTest.java`

**Interfaces:**
- Produces budget CRUD/revision history and usage output `{budget, spent, remaining, percent, status}` for total/category/member scopes.

- [ ] **Step 1: Write failing budget validation/history tests**

Test one active budget per household/month/scope, admin-only mutation, category/member ownership, positive amount, and revision rows containing old/new amounts plus actor/time.

- [ ] **Step 2: Write failing usage tests with literals**

```java
@Test
void categoryBudgetUsesOnlyConfirmedExpensesInMonth() {
    createBudget("2026-09", CATEGORY, foodId, 100_000L);
    createExpense("2026-09-02", foodId, 35_000L);
    createIncome("2026-09-03", salaryCategoryId, 10_000L);
    createExpense("2026-10-01", foodId, 20_000L);
    BudgetUsage usage = service.usage(context, YearMonth.of(2026, 9)).getFirst();
    assertThat(usage.spentCents()).isEqualTo(35_000L);
    assertThat(usage.remainingCents()).isEqualTo(65_000L);
    assertThat(usage.percent()).isEqualByComparingTo("35.0");
}
```

- [ ] **Step 3: Implement budget service and queries**

Never store spent totals. Query transactions with household/month/kind/scope predicates and aggregate cents in the database or service with overflow-safe addition.

- [ ] **Step 4: Run tests and commit**

```bash
./mvnw -q -Dtest=BudgetApiTest,BudgetUsageServiceTest test
./mvnw test
git add src/main/java/com/familyfinance/budget src/test/java/com/familyfinance/budget
git commit -m "feat: add household budgets and revision history"
```

---

### Task 5: Recurring rules, occurrences, and confirmation

**Files:**
- Create: recurring rule/occurrence entities, repositories, service, controller, DTOs under `ledger/recurring`
- Create: `src/main/java/com/familyfinance/config/SchedulingConfig.java`
- Create: `src/test/java/com/familyfinance/ledger/recurring/RecurringGenerationTest.java`
- Create: `src/test/java/com/familyfinance/ledger/recurring/RecurringConfirmationApiTest.java`
- Create: `src/test/java/com/familyfinance/ledger/recurring/RecurringConcurrencyTest.java`

**Interfaces:**
- Produces rule CRUD, occurrence list, daily generation job, and `POST /api/recurring-occurrences/{id}/confirm`.

- [ ] **Step 1: Write failing recurrence generation tests**

Cover monthly day 31 clamped to month end, weekly intervals, paused/ended rules, next due calculation, and unique `(rule_id,due_on)` preventing duplicate generation on rerun.

- [ ] **Step 2: Implement rule state and generator**

Use an injected `Clock`; scheduler calls a service method so tests never wait for real time. Generation creates occurrences only and never creates financial transactions.

- [ ] **Step 3: Write failing confirmation/concurrency tests**

```java
@Test
void confirmingOccurrenceCreatesExactlyOneTransaction() throws Exception {
    long occurrence = dueOccurrenceFor(memberUser);
    confirm(memberSession, occurrence).andExpect(status().isOk());
    confirm(memberSession, occurrence).andExpect(status().isOk());
    assertThat(transactionRepository.countBySource(RECURRING, occurrence)).isEqualTo(1);
}
```

Use concurrent confirmations and assert both responses identify the same transaction. Reject unassigned member, stale account/category, and cancelled occurrence.

- [ ] **Step 4: Implement locked idempotent confirmation**

Pessimistically lock occurrence, check status, create transaction with `source_type=RECURRING` and `source_id`, then mark confirmed in one transaction. Add database unique constraint on source pair.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw -q -Dtest=RecurringGenerationTest,RecurringConfirmationApiTest,RecurringConcurrencyTest test
./mvnw test
git add src/main/java/com/familyfinance/ledger/recurring src/main/java/com/familyfinance/config/SchedulingConfig.java src/test/java/com/familyfinance/ledger/recurring
git commit -m "feat: add idempotent recurring billing"
```

---

### Task 6: Ledger/budget/recurring acceptance

**Files:**
- Create: `src/test/java/com/familyfinance/acceptance/StageTwoLedgerSmokeTest.java`
- Create: `docs/acceptance/stage-2-ledger-checklist.md`
- Modify: `README.md`

**Interfaces:**
- Certifies migration, account/category/budget CRUD, recurring generation, confirmation, and restart persistence.

- [ ] **Step 1: Write failing real HTTP smoke flow**

Register owner, create account, create parent/child category, set category budget, create monthly recurring expense, generate occurrence with injected clock, confirm it, verify one transaction and updated budget usage, restart the same H2 file, and verify all state remains.

- [ ] **Step 2: Run RED then complete missing integration wiring**

Run: `./mvnw -q -Dtest=StageTwoLedgerSmokeTest test`

- [ ] **Step 3: Run full gate and commit**

```bash
./mvnw test
git diff --check
git add src/test/java/com/familyfinance/acceptance/StageTwoLedgerSmokeTest.java docs/acceptance/stage-2-ledger-checklist.md README.md
git commit -m "test: certify Stage 2 ledger and budgets"
```
