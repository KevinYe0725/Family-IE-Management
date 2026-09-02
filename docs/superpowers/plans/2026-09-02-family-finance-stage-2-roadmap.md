# Family Finance Stage 2 Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved second-stage multi-user household finance workspace through five independently reviewable, always-runnable implementation plans.

**Architecture:** A Spring Boot modular monolith gains versioned Flyway migrations, household memberships, budget/asset/investment/loan domains, and Tushare end-of-day A-share quotes. A React + TypeScript + Semi Design frontend is built by Maven and replaces the native SPA only after all backend contracts are stable.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring Security, Spring Data JPA, Flyway Core, H2, React, TypeScript, Vite, Semi Design, TanStack Query, Vitest, React Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-02-family-finance-stage-2-design.md`

## Global Constraints

- Preserve all Stage 1 routes and persisted data until the replacement frontend and migration acceptance pass.
- Money remains positive/negative integer cents according to the owning domain; never use binary floating point for stored financial values.
- Every household resource is authorized from the authenticated membership, never from a client-provided household id.
- All schema changes use Flyway; Hibernate runs with `ddl-auto=validate` after migration bootstrap.
- Tushare is read-only, A-share daily close only, and disabled cleanly when `TUSHARE_TOKEN` is absent.
- No password, invite token, CSRF token, Tushare token, or full upstream response enters logs.
- Each plan ends with full Java tests, the currently applicable frontend tests, file-H2 restart verification, and a review gate.
- Existing untracked course documents remain untouched and are never staged.

---

### Task 1: Execute the five plans in dependency order

**Files:**
- Read: `docs/superpowers/plans/2026-09-02-stage-2-foundation-identity.md`
- Read: `docs/superpowers/plans/2026-09-02-stage-2-ledger-budget-recurring.md`
- Read: `docs/superpowers/plans/2026-09-02-stage-2-assets-investments-market.md`
- Read: `docs/superpowers/plans/2026-09-02-stage-2-loans-notifications-reporting.md`
- Read: `docs/superpowers/plans/2026-09-02-stage-2-react-feishu-frontend.md`

**Interfaces:**
- Plan 1 produces Flyway, memberships, registration, invitations, and permission services.
- Plan 2 consumes memberships and produces accounts, hierarchical categories, budgets, recurring occurrences, and confirmation flows.
- Plan 3 consumes accounts/memberships and produces assets, investments, Tushare price snapshots, and portfolio outputs.
- Plan 4 consumes ledger/assets/investments and produces loans, reminders, net-worth snapshots, and consolidated reporting.
- Plan 5 consumes all stable REST contracts and produces the React/Semi interface and final cross-platform acceptance.

- [ ] **Step 1: Complete and review Plan 1**

Run its complete test gate and record the reviewed head before Plan 2 starts.

```bash
./mvnw test
git log -1 --oneline
```

- [ ] **Step 2: Complete and review Plan 2**

Verify Stage 1 transactions still load after account/category migration and recurring confirmation remains idempotent.

```bash
./mvnw test
git diff --check
```

- [ ] **Step 3: Complete and review Plan 3**

Run all market tests with a local HTTP stub and verify no real token is used by tests.

```bash
./mvnw test
git grep -n 'TUSHARE_TOKEN' -- ':!README.md' ':!docs'
```

- [ ] **Step 4: Complete and review Plan 4**

Verify repayment/recurring confirmations, notification generation, and snapshots remain idempotent after restart.

```bash
./mvnw test
git diff --check
```

- [ ] **Step 5: Complete and review Plan 5**

Run Java, React, browser, Windows startup, migration, and packaging gates.

```bash
./mvnw clean verify
```

- [ ] **Step 6: Final whole-branch review**

Review the complete Stage 2 range against the approved spec, fix all Critical/Important findings in one consolidated wave, rerun all gates, and use `superpowers:finishing-a-development-branch` for integration.

```bash
git diff --check main..HEAD
./mvnw clean verify
```
