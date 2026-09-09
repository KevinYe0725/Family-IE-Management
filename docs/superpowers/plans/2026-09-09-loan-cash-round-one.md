# Loan and Cash Round One Implementation Plan

> **For agentic workers:** Use the focused task briefs below; preserve independent file ownership. Check off verification only from observed results.

**Goal:** Close the first-round loan/account/cash-flow/homepage loop while retaining all existing repayment behavior.

**Architecture:** Reuse the posting engine and immutable journal sources. Add only a loan net-disbursement field and read-only cash queries; do not duplicate financial transactions.

**Tech Stack:** Java 17, Spring Boot, JPA/JDBC, MySQL/H2, React, existing ActionDialog and bank picker.

**Spec:** `docs/design/loan-asset-integration.md`

## Global Constraints

- No clearing data, deployment, push, or changes to `.idea`.
- No local HTTP servers or `*SmokeTest`; only explicit named MockMvc/unit tests.
- Root owns frontend and integration. Loan task owns loan files and V45. Cash task owns new cash read services/controllers and tests, no shared service rewrites.
- One Maven process at a time; agents must request the slot. Never invoke unfiltered Maven `test`/`verify`.
- BigDecimal amounts, two-decimal responses, server-authoritative cash posting, household isolation, CNY-only loans.

## Task 1: Net disbursement and explicit withheld fee

Files: LoanCreateRequest, Loan/LoanResponse, LoanService, LoanAccountingService; V45__loan_disbursement_amount.sql in both dialects; LoanDisbursementAmountApiTest.

Interface: optional `disbursementAmount` on loan create; only DISBURSEMENT accepts it, absent means principal for compatibility. Response adds `disbursementAmount` and `withheldFee`. Actual amount >0 and <=principal. Fee is principal-actual and uses the existing payment expense category; liability/plan remain principal. Preserve overloaded request constructors.

- [x] Red regression: principal 10000, receipt 9800 -> cash +9800, liability +10000, expense +200, net worth -200. Opening loans cannot receive disbursementAmount. Foreign/archived/uninitialized accounts rejected. Replay not duplicated.
- [x] Add nullable decimal field; legacy null means full receipt. New create persists explicit receipt. Accounting journal: CASH debit receipt, EXPENSE debit fee, LOAN credit principal.
- [x] Protect corrections: do not silently replace a net receipt with principal. Reject principal/origination financial corrections for fee-bearing loans unless safely preserving the existing receipt; metadata and repayments remain supported.
- [x] Named regression plus existing loan accounting/payoff tests, release Maven slot.

## Task 2: Cash position and authoritative cash movements

Files: new CashPositionController/Service and CashMovementController/Service/Response under accounting, focused tests.

Interfaces:

```ts
type CashPosition = {asOf:string; currency:'CNY'; availableCash:string|null; knownAvailableCash:string; uninitializedCount:number; unconverted:Array<{accountId:number;currency:string;nativeAmount:string}>};
type CashMovement = {id:string; journalId:number; sourceType:string; sourceId:number; effectiveOn:string; accountId:number; accountName:string; currency:string; kind:'income'|'expense'; amount:string; internalTransfer:boolean; description:string};
// GET /api/cash-position
// GET /api/cash-movements?month=&accountId=&bankAccountId=&kind=&page=0&size=20
// Returns an ordinary data Page<CashMovement> with items/page/size/totalElements/totalPages/hasNext.
```

- [x] Red tests: loan receipt appears although it is not a manual transaction; linked-bank parent not double-counted; movement native currency retained; current source corrections not doubled; foreign/missing FX/initialization unknown; cross-household IDs do not leak.
- [x] Position uses current CASH balances and same current-date valuation rates as NetWorthService, Shanghai Clock, read-only repeatable snapshot. No bank parent or credit limit as cash.
- [x] Movements join ledger_sources.current_journal_id to journals/entries/accounts, aggregate CASH legs per current journal/account and filter/paginate. Positive debit-credit=in, negative=out; omit zero/noncash. Retain source identifiers for existing accounting history dialog; mark transfers as internal, do not report them as income/expense.
- [x] Run named tests after Maven slot is free.

## Task 3: Loan/page integration

Files: LoansPage, new cash flow panel, TransactionsPage, DashboardPage/HomeCashPosition, write-refresh, API types where needed, focused UI tests.

- [x] Add optional actual receipt only for new DISBURSEMENT loans. Default full principal until user overrides, show derived fee and selected-account before/after receipt before save. Clear/omit on funding-mode change. Do not let UI authoritatively post computed money.
- [x] Add home cash metric independently of net-worth value; unknown stays unknown with action, no stock or parent-account double count.
- [x] Add 收支记录 / 资金流水 view switch. Existing chart/filter/CRUD remains in record mode. Cash view shows native-currency source-labelled movements with readonly audit popup. No edit/delete buttons for generated movements.
- [x] Add cash-position/cash-movements to shared refresh dependencies so all relevant persisted writes refresh immediately.
- [x] Regression tests: unknown not zero, foreign native amounts, no automatic repayment/extra writes, modal history, mode switch and fee payload.

## Completion

- [x] Root inspect diffs, named backend tests, full frontend suite/typecheck/build, independent review.
- [x] Report completed first-round scope separately from second/third-round work and publication status.

## Observed verification

- Loan backend: 97 named tests passed before integration; cash readers: 13 named tests passed.
- Integrated loan receipt/cash-position/cash-movements assertions passed together with the V23→V45 legacy-column preservation check (37 targeted tests).
- Wider named backend run: 148 passed, one legacy select-star expectation failed because V45 adds a column; the fixed test compares every legacy column by name and separately asserts the new field remains null for historical loans, then passed.
- Full frontend run: 388 passed. Review added two full-receipt correction cases; final targeted run 15 passed. Typecheck and production build passed.
- Independent review found the correction preview using old receipt; corrected to follow principal only for existing zero-fee loans, and scoped re-review confirmed it.
- No local HTTP startup tests, cloud data changes, push or deploy. Browser visual acceptance and MySQL deployment verification remain release checks.

## Rulings I made

- Deliver the agreed design in three separately testable rounds; only the first is implemented here. Purchase contribution/relationship and sale settlement remain explicit later work.
- Fee-bearing original cash terms are protected against correction until a dedicated reversal/correction workflow is designed; normal repayment and rate/term adjustments remain available without rewriting original fee cash.
- Preserve existing record view and add a distinct all-cash movement view rather than creating duplicate FinancialTransaction rows for asset/loan events.
