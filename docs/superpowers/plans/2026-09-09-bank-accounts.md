# Bank Accounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Group bank currency balances under one bank account without changing financial posting contracts.
**Architecture:** Add bank_accounts parent and nullable financial_accounts.bank_account_id. Existing business identifiers always refer to currency children; a shared UI resolves the selected bank and currency before submission.
**Tech Stack:** Java 17, Spring Boot, JPA/JDBC, MySQL 8/H2, React/TypeScript, existing dialogs/styles.
**Spec:** docs/superpowers/specs/2026-09-09-bank-accounts-design.md

## Global Constraints

- No production reset, server access, push or deployment in implementation steps. Preserve unrelated .idea edits.
- accountId/fundingAccountId keep child semantics; bankAccountId is a distinct optional field. No automatic FX, double counting, currency expansion or calculation rewrites.
- CNY/HKD/USD remain supported; loan/assets/recurring stay CNY-only. Currency children use existing opening and cash posting protections.
- Do not start local HTTP business servers. Focused MockMVC/H2/unit tests are permitted. One Maven compiler owner at a time.
- New migrations V41 only; never change released migrations. Update migration version assertions including CI smoke version lists.

### Task 1: Bank account backend foundation

Files: ledger/BankAccountDtos.java, BankAccountService.java, BankAccountController.java, FinancialAccount.java, AccountResponse.java, AccountService.java; V41 migrations; BankAccountApiTest.java and migration assertions.

Interfaces: GET /api/bank-accounts -> envelope data BankAccount[] with {id,name,bankName,cardLastFour,archivedAt,accounts:Account[]}. POST accepts {name,bankName,cardLastFour,balances:[{currency,openingBalance,openingOn}]}; PATCH /{id} accepts name/bankName/cardLastFour; POST /{id}/balances accepts {currency,openingBalance,openingOn}; DELETE /{id} archives. Writes use Idempotency-Key except archive. Account adds nullable bankAccountId,bankAccountName. Parent table bank_accounts has household_id. Child association uses bank_account_id. Legacy BANK create automatically creates one parent for compatibility. Grouped child metadata edits route/restrict consistently.

- [x] Write MockMVC cases: create two currencies => accounts IDs distinct, bankAccountId same; duplicate currency rejected; another household cannot add/edit; repeated create no duplicate cash; parent archive with balance/binding rejected. Example assertion: jsonPath("$.data.accounts[0].bankAccountId").value(parentId).
- [x] Run BankAccountApiTest to observe missing endpoints fail.
- [x] Implement migration, household-locked service and controller via AccountingCommandExecutor; reuse AccountService cash opening within same transaction and unique request keys. Preserve per-child names required by unique household/name index. Parent edits synchronize children without changing their IDs/currency. Legacy accounts each get independent parent, no balance writes.
- [x] Run BankAccountApiTest, AccountApiTest, MultiCurrencyAccountApiTest, migration suites; verify no posted cash at parent level and no cross-family reference.
- [x] Report exact DTO and test outcomes; root coordinates commit after review.

### Task 2: Shared UI selection and management

Files: api/contracts.ts; features/ledger/BankAccountPicker.tsx, BankAccountsPanel.tsx, FundingAccountCreator.tsx, account-label.ts, AccountIdentity.tsx; existing business forms and AccountInitializationGuide.tsx.

Interfaces: picker consumes Account[], currency, value(child ID string), onChange(child ID string), request for contextual creation. It groups by bankAccountId; independent cash/wallet entries remain selectable. If no currency supplied, explicitly choose currency. Parent-only bank list fetch enables adding missing currencies. New child success invalidates accounts/bank-accounts and preserves parent form.

- [x] Add React tests for grouped display, USD child resolution, currency switch same bank, no fallback on missing USD, and nonbank compatibility; assert onChange receives child ID not parent.
- [x] Run focused tests and observe missing component/behavior failure.
- [x] Implement shared picker and bank management dialogs using existing design tokens. No native unstyled button strips. Management separates nonbank rows from grouped cards; zero balance requires explicit confirmation.
- [x] Replace account selection in transactions, transfers, FX, investment funding, loan panels, assets and recurring; keep DTO mutation payloads unchanged. New/edit transaction gains explicit currency selector, original accounting record IDs remain unchanged.
- [x] Run all frontend tests, typecheck/build, plus targeted integration cases; update outdated UI assertions only when new behavior warrants.

### Task 3: Parent filtering and archive/integration acceptance

Files: TransactionFilter.java, TransactionFilterParser.java, transaction repository/specifications/controller; reporting/ExportController.java; TransactionsPage.tsx; corresponding tests.

- [x] Add test that bankAccountId returns both currency children and excludes another bank, with matching paged totals/export. Assert foreign family bank selector rejects.
- [x] Run targeted failing tests, then add optional bankAccountId with source-compatible Java record constructor overloads for existing callers; combine parent and child filters by AND.
- [x] Wire grouped filter, preserve child filter; ensure all currencies remain labeled and no mixed-currency arithmetic.
- [x] Run backend non-HTTP suite, frontend all, deployment script tests; independent review of financial safety and spec coverage.
- [x] Record verification limits and remaining release reset procedure; do not claim deployment.
