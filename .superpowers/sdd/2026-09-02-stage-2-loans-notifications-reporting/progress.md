# SDD ledger — plan: docs/superpowers/plans/2026-09-02-stage-2-loans-notifications-reporting.md

## Setup

- Worktree/branch: `.worktrees/family-finance-stage-2` / `codex/family-finance-stage-2`
- Base head: `c8c30e2`
- Existing migrations: V1–V10; Plan 4 starts at forward-only V11.
- User priority: product-first. Implement in three waves with focused financial/state-machine tests and clean package smoke; skip repeated exhaustive matrices.

## Interface scan and rulings

- Wave 1 (Tasks 1–3) produces V11, deterministic amortization, loan contract/schedule APIs; consumed by repayment and reporting.
- Wave 2 (Tasks 4–5) produces idempotent installment/prepayment transactions and unified reminder lifecycle; consumed by net-worth acceptance.
- Wave 3 (Tasks 6–7) produces net worth/debt/budget/investment reporting, snapshots and one real HTTP restart smoke.
- Integer cents and bounded scaled rates are mandatory; paid schedules and sourced ledger rows are immutable history.
- Owner/admin manage loans; only explicitly assigned users confirm; all mutations use fresh post-lock household authorization.
- Notifications/snapshots use natural-key idempotency and are resolved, never deleted.
- Net worth counts financial accounts, non-cash assets, investment market value and open loan principal exactly once.
- V1–V10 never change; startup preflight dynamically discovers V11+.

## Task status

- [x] Tasks 1–3 — V11, amortization, loan contracts/schedules
- [x] Tasks 4–5 — repayment/prepayment and notifications
- [ ] Tasks 6–7 — consolidated reporting, snapshots and acceptance

## Review log

- Product-first execution started.
- Wave 1 complete: V11 forward migration, pure calculator, loan contract/schedule APIs and focused tests. Commit pending controller handoff.
- Wave 2 complete: V12 forward migration, locked installment confirmation, idempotent prepayment and a household notification lifecycle with daily Shanghai generation.
