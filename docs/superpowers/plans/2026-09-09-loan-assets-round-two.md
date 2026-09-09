# Loan and Asset Round Two Implementation Plan

**Goal:** Add full financed purchase value + cash contribution, and metadata-only two-way financing/collateral links. Preserve round-one changes and existing accounting behavior.

**Spec:** `docs/design/loan-asset-integration.md`, second round.

## Constraints

- Do not touch CI workflows/scripts/operations docs or `.idea`; these contain other work.
- No data clearing, push or deployment. No local HTTP servers, no unfiltered Maven test/verify, no SmokeTest.
- Backend worker owns loan/asset backend, V46 and named tests. Root owns frontend and migration-version assertions.
- Use same atomic posting/household locks/idempotency, BigDecimal with two-decimal settlement, CNY funding only.

## Task 1: Full purchase price and cash contribution

Files: Loan/LoanCreateRequest/LoanResponse/LoanService/LoanAccountingService/LoanRequestHistory; LoanPurchasedAssetService; V46__loan_purchase_links.sql (H2 + MySQL); named regression LoanPurchaseContributionApiTest.

Create contract adds optional `purchaseValue`, `ownContributionAccountId`, `assetRelation` (`FINANCING` or `COLLATERAL`). `purchaseValue` only allowed for FINANCED_PURCHASE and >=principal; absent defaults to principal solely for old-client compatibility. Actual UI requires complete purchase price. Positive difference requires confirmed active household CNY cash account. Zero difference does not need an account.

Loan response adds two-decimal `purchaseValue`/`ownContribution` and nullable `ownContributionAccountId`, plus `assetRelation` and `linkedAssetName`. Nonpurchase modes return purchase values null/0. Store nullable purchase_value/own_contribution_account_id/asset_relation. Legacy metadata defaults preserve prior behavior; never rewrite old ledger entries.

- [x] Test price 200000, loan 150000, own cash 50000: asset +200000, loan +150000, cash -50000, net worth unchanged; insufficient cash rolls back loan+asset+journal, no orphan.
- [x] Original purchase journal debits full asset, credits principal and own cash; no phantom loan cash receipt. Auto-asset initial values use full price.
- [x] Preserve round-one net-receipt behavior, V1/V2 old command digest replay, new command body mismatch rejection; purchased origin corrections remain immutable.

## Task 2: Metadata-only financing/collateral relationship

New endpoint `PUT /api/loans/{id}/asset-link`, body `{assetId:number|null,relation:'FINANCING'|'COLLATERAL'|null,expectedAssetId:number|null,expectedRelation:'FINANCING'|'COLLATERAL'|null}`, Idempotency-Key required through existing AccountingRequests convention; returns LoanResponse. Server must lock household, authorize admin, compare expected existing link, validate same-family active asset; metadata change must not create cash/asset/loan journals. New endpoint replays return current response without duplicating changes.

- Nonpurchased loans may attach/replace/clear links. FINANCING uses current loan/asset type compatibility; COLLATERAL allows an existing asset regardless of loan type. Plan/default corrections preserve collateral role.
- Auto-created purchase links cannot be moved/cleared/reclassified, because their origination source belongs to that asset. Keep original source/history identifiers.
- Effective legacy relation is FINANCING if a link exists, else null.
- New `GET /api/assets/{id}/loans` returns `{assetId,financedPrincipal,referenceEquity,loans:[{loanId,name,status,relation,remainingPrincipal}]}`. Sum only ACTIVE FINANCING current principal; collateral never subtracted. One asset may link several loans, each counted once. Read existing archived/closed links for history, household isolated.
- [x] Test two financing loans + one collateral loan; metadata change leaves journals/cash/networth unchanged; wrong household/role/stale expected link rejected; old links and round-one corrections compatible.

## Task 3: Frontend forms and bidirectional details

Files: LoansPage, new LoanPurchaseSummary, AssetsPage, new AssetDetailsDialog, API contracts, relevant tests.

- [x] New direct purchase requires `完整购置金额`; display loan/own contribution and selected account before/after; payload omits purchase fields for other modes.
- [x] Existing asset association offers FINANCING/COLLATERAL. Show referenced asset name and open an asset-details modal.
- [x] Asset details shows value, financing balance, reference equity, and full linked-loan list. Admin can associate an existing loan with explicit replacement warning and stable command key; do not recreate it. Loan links open existing plan via validated `loanId` deep link.
- [x] Asset creation/edit/valuation/disposal/history all use shared ActionDialog; avoid two visible working dialogs. Preserve existing data/permission validation.

## Verification and handoff

- [x] Named MockMvc/H2 tests only, update latest-version expectations (including CI-only smoke assertion) to46 without running HTTP tests locally.
- [x] Full frontend test/typecheck/build, independent scoped review.
- [x] Mark this round separately from sale/repayment orchestration; no claim that round3 is complete.

## Observed results

- Backend implementation: 31 new +58 adjacent named MockMvc/H2 tests passed; root wider named loan/asset/cash/migration regression command subsequently passed.
- H2 reached V46; legacy migration assertions include the CI-only smoke version list (not executed locally). MySQL V46 was not applied to a server in this round.
- Full frontend run:396 passed. One additional regression demonstrated the old-purchase cash-preview defect; corrected it, then final focused9 passed with typecheck/build.
- Reviewer confirmed only-new-purchase cash preview after correction; no remaining reported money/permission blocker.
- Added originPurchase flag for protected purchase links and confirmed metadata-only unlink for other links. No ordinary financial journals are generated by link changes.
- No cleanup of user data, no localHTTPserver, and no push/deployment by this task. External CI/script work was left untouched.
- Third-round sale/repayment orchestration remains pending; no claim of full three-round completion.
