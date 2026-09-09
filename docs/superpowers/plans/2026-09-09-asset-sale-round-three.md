# Asset Sale and Loan Settlement — Round Three

**Goal:** Finish sale/retain-debt/partial-repayment/payoff integration without fictitious cash, duplicate entries, or lost history. Implements round three of `docs/design/loan-asset-integration.md`.

## Constraints

- Preserve all uncommitted rounds one/two. Do not modify CI/scripts/operations docs or `.idea`.
- No push/deploy/data clearing or local HTTP services. Maven must use direct compile/resources/surefire goals with explicitly named MockMvc/unit tests; never unfiltered test/verify or SmokeTest.
- Settlement backend worker owns asset/loan orchestration, FinancialTransaction entity, V47 and settlement tests. Read-model worker owns transaction response/summary/export and ledger frontend only. Root owns asset-sale UI, loan-history UI, shared frontend contracts, version assertions and integration.
- One Maven process at a time. Settlement worker owns initial slot, then releases.
- BigDecimal calculation; settlement/display two decimals. Same household/admin/assigned-confirmation rules as existing loan actions. Read preview is not authorization; commit rechecks locked state.

## Wire contract

```ts
type SaleLoan = {loanId:number; mode:'PAYOFF'|'PARTIAL'; additionalPrincipal?:string; strategy?:'REDUCE_TERM'|'REDUCE_PAYMENT'|'ADJUST_TERM'; targetPeriods?:number; interestAmount?:string};
type SaleDraft = {disposedOn:string;proceeds:string;fee:string;cashAccountId:number|null;repaymentAccountId:number|null;route:'VIA_ACCOUNT'|'DIRECT';repayments:SaleLoan[];retainUnselectedLoans:boolean};
type SaleLoanPreview = {loanId:number;name:string;mode:'PAYOFF'|'PARTIAL';principal:string;interest:string;total:string;remainingPrincipal:string};
type SalePreview = {assetId:number;assetName:string;disposedOn:string;route:'VIA_ACCOUNT'|'DIRECT';proceeds:string;fee:string;bookValue:string;bookGain:string;totalPrincipal:string;totalInterest:string;totalRepayment:string;netSettlement:string;balances:Array<{accountId:number;accountName:string;currency:string;before:string;change:string;after:string}>;loans:SaleLoanPreview[];retainedLoans:Array<{loanId:number;name:string;remainingPrincipal:string}>;canConfirm:boolean;blockers:string[];planToken:string};
type SaleResult = {saleId:number;assetId:number;preview:SalePreview;recordedAt:string};
// POST /api/assets/{id}/sale-preview body SaleDraft: read-only, returns SalePreview
// POST /api/assets/{id}/sale body {draft:SaleDraft,planToken:string}, Idempotency-Key: stable UUID; returns SaleResult
// GET /api/assets/{id}/sale returns SaleResult|null (null for legacy/no-sale receipt)
```

Fees mean selling costs deducted from sale proceeds, not a separately invented bank transfer. Gross proceeds >=0, fee >=0; negative net requires real cash support. No selected loans means retain debt; active unselected loans require explicit `retainUnselectedLoans:true`.

## Task 1 — Atomic settlement backend

- [x] Preview source asset/value/date, CNY active confirmed cash accounts, current linked loans, due-interest obligations, partial strategies/allowed terms and after-balances. Prevent unrelated/duplicate loan selections. Selected loan payment account defaults to receipt account; DIRECT uses the same account for receipt/top-up and context.
- [x] VIA_ACCOUNT: actual asset cash = proceeds-fee; then repay selected loans from chosen repayment account (can differ). Only receipt-account cash supports that same account; never auto-transfer to a different repayment account. Derive child tokens using projected sequential balances; preserve existing plan/assignee checks and recheck on commit.
- [x] DIRECT: cash changes only by proceeds-fee-totalRepayment. Use non-CASH `ASSET_SALE_CLEARING:<assetId>` (kind ASSET) for the buyer-paid portion; asset journal debits clearing, loan settlements credit clearing. Clearing must end at zero in the outer transaction, must never be counted as cash or assets. No fake CASH receipt/payoff legs.
- [x] Reuse loan planning/settlement behavior through explicit internal funding context, not ThreadLocal and not a bypassable public request field. For DIRECT, mark all generated repayment FinancialTransactions with nullable `asset_settlement_id` (getter `getAssetSettlementId()`, `hasCashImpact()` false iff nonnull). Ordinary repayments remain unchanged. Keep real CNY account linkage for settlement context; record reads must clearly label this noncash debt repayment.
- [x] DIRECT loan previews/batch history retain settlement totals but add `cashImpact:false` and `settlementAssetId`; balanceAfter must reflect no extra cash debit by the child. Extend old constructors/JSON compatibility so historical null flags behave as ordinary cash. Expose flags on LoanPrepaymentResponse and LoanRepaymentResponse; root updates UI wording.
- [x] Read and mutation previews must be single coherent snapshots; avoid calling existing REQUIRES_NEW preview inside the outer commit. Parent plan token binds asset value/date, relevant loan tokens/relations and balances. Stale preview rejects the entire operation. Full-request immutable sale receipt makes duplicate and uncertain retries replay the original result before checking archived state.
- [x] Asset disposed/archived only after all child actions succeed; resolve due notifications via existing helpers. Roll back asset, loan plans, transactions, journals, receipts on any failure. Legacy dispose must reject assets with active loans with a clear instruction to use the sale flow, while keeping old no-loan disposal behavior/replays.
- [x] Archive zero-value assets may retain references to CLOSED/ARCHIVED zero-principal loans; no deletion of relation/history. Outstanding references still block ordinary archive. Sale with explicit retained debt preserves those loans and their asset links.
- [x] Preserve book-gain read: selling costs reduce disposal book gain, repayment principal does not; loan interest remains loan expense. Add V47 only (nullable FT settlement FK + immutable asset_sale_receipts); no rewriting old money.
- [x] Tests: retain debt; via payoff; partial due+extra with both strategies; direct payoff/partial; shortfall and different accounts; stale quote, idempotent replay, second child failure rollback, auth/household/assignee, clearing zero, old disposal compatibility, zero proceeds and historical references.

## Task 2 — Noncash-aware read models

- [x] FinancialTransaction producer contract is `getAssetSettlementId()`/`hasCashImpact()` from Task1. Add `cashImpact` and `settlementAssetId` to TransactionResponse, name noncash account as buyer settlement. Do not imply a withdrawal from its context account.
- [x] TransactionSummary excludes noncash settlement amounts from cash totals/categories/daily; add `nonCashTransactionCount` default0 and preserve existing constructors. Read list can retain the loan record with clear noncash status. CSV must explicitly identify noncash rows (append a cash-impact column, don't silently discard debt settlement).
- [x] Update ledger row/details/chart UI flags and labels; no direct edit/delete of noncash records. Existing generated records and ordinary cash flow remain supported. Refresh sale-preview must NOT invalidate reads as if it were a write.
- [x] Test mixed ordinary/direct rows, unchanged cash totals, CSV identity, no raw-bank cash claim, legacy flag compatibility. No migration/entity edits in this task.

## Task 3 — Asset sale UI and loan history

- [x] Replace asset disposal form with shared centered sale dialog, selecting keep/partial/payoff per related active loan and two real payment routes. Keep old values and inputs when preview fails.
- [x] Two-step review: draft → authoritative preview → explicit confirm. Show gross/fee/debt/net and each account before/change/after, remaining loans and blockers. No submission from opening dialog. Plan/body held immutable during uncertain retries; changes require a fresh preview/key.
- [x] Sale receipt and loan history can be revisited. Direct settlement says buyer paid and no separate account debit; never show hypothetical negative child account balances as real deductions.
- [x] Existing assets, history, valuation, archived filters and roles remain usable. No small explanatory text except necessary status/confirmation.

## Gate

- [x] Named backend + integration/upgrade tests; frontend typecheck/full suite/build; independent review and focused fixes. Update expected latest version to47, including CI-only list without running HTTP tests locally.
- [x] Report actual completion and release gates separately. Keep all original data and previous rounds intact.

## Execution checkpoint

- Asset-sale dialog and stored receipt are wired into the existing asset page/detail, with separate draft/review/confirm stages. Required debt-retention acknowledgement, source-account projection, extra-principal wording and frozen retry key/body are covered by focused frontend checks.
- Direct-funded loan history no longer claims a separate bank deduction. The new settlement metadata is optional for legacy frontend payloads.
- Review finding: confirmed asset sales must refresh loan schedules, repayment history and term/policy reads; fixed in write-refresh, while sale-preview remains read-only. Asset financing query keys are shared across sale/detail views.
- Review finding: payoff preview must validate the effective accounting member/category before approving a sale; backend worker is adding the regression and fix.
- Ruling: sale fees are costs withheld from gross proceeds; the UI states this explicitly. A separately paid fee would require a distinct real transaction, not an invented bank movement.
- Ruling: uncertain submissions keep the original frozen draft, token and key and block navigation; retry remains enabled. Changes require a definitive failure or returning from an unsubmitted preview.
- Frontend full suite at this checkpoint: 72 files / 415 tests passed, typecheck passed. A further direct-funded loan-history case was added afterward and remains in the final verification gate.
- No commit, push, deployment, cloud mutation, or local HTTP service startup performed.

## Final verification — 2026-09-09

- Task 1, Task 2 and Task 3 complete in the existing Stage 2 worktree, without committing unrelated IDE files.
- Frontend: `npm run typecheck`, `npm test -- --run` (72 files / 419 tests), and `npm run build` passed. Existing large-chunk warning remains; it is not a build failure.
- Backend: 20 explicitly named MockMvc/unit/migration classes cover 203 tests, all passing in the final reports. Initial wide run had one test-only failure: SELECT * added a nullable V47 column to the old transaction snapshot. The corrected migration test compares every original column and row and separately asserts no old noncash attribution or sale receipt was fabricated; its focused rerun passed. Production code did not change after the wide run.
- Additional existing payoff/combined/strategy/repayment coverage: 53 tests passed in the backend worker's targeted runs (13 payoff, 26 combined, 11 strategy, 3 repayment).
- Root backend command uses only `resources:resources compiler:compile resources:testResources compiler:testCompile surefire:test`, with:
  `-Dtest=AssetSaleApiTest,LoanDisbursementAmountApiTest,LoanPurchaseContributionApiTest,LoanAssetLinkApiTest,CashReadApiTest,CashAccountingApiTest,WealthAccountingApiTest,TransactionApiTest,NonCashTransactionReadModelTest,TransactionSummaryApiTest,CsvExportApiTest,FlywayFreshDatabaseTest,FlywayStageOneUpgradeTest,LedgerIntegrityMigrationTest,LoanRepaymentBatchMigrationTest,BankAccountMigrationTest,InvestmentPlanMigrationTest,InvestmentAccountCreatorMigrationTest,LedgerStageTwoMigrationTest,LoanNotificationMigrationTest`.
- Independent review: payoff dimension validation and stale loan-read refresh findings closed. Migration test correction independently reviewed without weakening original-row preservation.
- UI receipt shows gross proceeds, selling fees, due principal/interest and extra principal, real account changes, remaining debt and optional new term/payment. Ordinary and direct-funded loan histories remain distinguishable.
- Auth/network recovery keeps the same key/body/token; 401/403 explains restoring the same account in another tab. Unresolved submission blocks navigation but not explicit retry.
- Deployment boundary: local H2/MockMvc and frontend verification only; no listening HTTP service, no browser visual acceptance claim, no live MySQL migration, no cloud mutation, no commit/push/deploy. Preserve worktree and current branch for the user's release decision.
