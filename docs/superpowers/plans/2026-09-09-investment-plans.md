# Confirmed recurring investment Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development; one backend implementer, parent builds independent frontend against fixed contract, independent review before handoff.

**Goal:** 到期通知、实际成交确认后才扣现金的定投计划。
**Architecture:** Persist plans and immutable occurrence snapshots; scheduled generation creates notifications only. Confirmation calls existing investment accounting inside one transaction, locked per household/occurrence, unique occurrence trade identity. UI reuses stock search, DateField and accounting preview.
**Tech Stack:** Java17 Spring Boot JDBC/JPA Flyway H2/MySQL, React Semi UI.
**Spec:** User-approved design in this document: weekly/biweekly/monthly plans, month-end clipping; create/edit/pause/resume/end; pending/confirmed/skipped occurrences, snooze2h/tomorrow; household authorization; no auto trading, FX or funds creation. Existing pending items survive schedule edits/pause/end. Confirm only actual BUY with actual price/quantity/fee/date. In-app notifications only.

**Execution completed 2026-09-09:** Tasks1–3 implemented and independently reviewed (UI issues plus MySQL-current-read issue fixed). Final frontend319 passed, Java697 passed/2conditional skips, no real HTTP startup tests run locally. Plan DTO additionally returns full `security`; occurrence returns current/reversed trade context; page includes pendingCount and pagination flags. See `docs/acceptance/investment-plans.md` for behavior, rationale and validation boundaries. No push/deployment performed in this implementation turn.

## Global Constraints
- BigDecimal amounts, no balance or position writes until actual confirmation. Reuse InvestmentTradeService and AccountingCommandExecutor, no duplicated posting engine.
- Funds must come from the investment account's linked concrete cash account; same currency and initialized. Snapshot fundingAccountId and reject confirmation if link changed; user updates plan for future occurrences or records past transaction manually instead of silently changing source.
- Mutation permissions match investment trades (owner/admin), assigned responsible active household member receives notification; other admins may act, actor recorded.
- Persist each generated occurrence's plan amount, accounts, security and due date. Unique(plan_id,due_on); bounded catchup continues across runs, deterministic monthly anchor (31→Feb last day→Mar31). Paused time is not backfilled on resume.
- No new dependencies, no local business app startup, no production data mutation or deployment without current authorization. Preserve staged .idea changes. H2/MySQL new V39 only.
- UI existing palette #4b6bee/#20272e/#67727e/#e5e9ee/#f7f8fa, existing font; Binance-inspired form + summary, not Binance branding. Prominent amount; explicit footer “仅创建提醒，不会自动买入或扣款”.

### Task 1: Backend recurring investment engine
Own src/main Java, migrations, src/test Java only; do not modify frontend. Send exact DTO contract early.

API proposal (ApiEnvelope, strings for money):
- GET /api/investment-plans → {plans:[Plan],occurrences:[Occurrence]}; authenticated household, bounded ordered result.
- POST /api/investment-plans and PATCH /api/investment-plans/{id}: {name,accountId,securityId,amount,frequency:WEEKLY|BIWEEKLY|MONTHLY,firstDueOn,assignedUserId}; stable request Idempotency-Key create.
- POST /api/investment-plans/{id}/state {state:ACTIVE|PAUSED|ENDED}.
- POST /api/investment-plans/occurrences/{id}/confirm {quantity,price,fee,tradedOn}; returns existing/new linked transaction, exactly once even different keys, rollback on insufficient funds.
- POST /api/investment-plans/occurrences/{id}/skip {reason}; POST .../snooze {option:TWO_HOURS|TOMORROW}.
- Plan fields id,name,accountId,accountName,fundingAccountId,securityId,securityName,symbol,currency,amount,frequency,firstDueOn,nextDueOn,assignedUserId,state.
- Occurrence fields id,planId,planName,accountId,accountName,fundingAccountId,securityId,securityName,symbol,currency,amount,dueOn,state:PENDING|CONFIRMED|SKIPPED,remindAt,tradeId,actualAmount,reason.
- reuse /api/households/current/members or existing membership endpoint for responsible users; tell parent exact endpoint.

- [ ] Write failing API/service tests: create doesn't post, due generation doesn't post and deduplicates, one confirmation posts and duplicate is idempotent, insufficient balance rolls back, other household denied, member mutation denied, monthly anchor, pause/resume, snapshot edits, snooze notification dedup.
- [ ] Implement V39 two dialect migrations + service/controller/scheduler/notification integration. No runtime optional stubs. Preserve source/link identity; handle ledger trade reversal without falsely allowing duplicate reconfirmation.
- [ ] Run tests including migrations updated expected latest version39; no weakening existing data guards. Use JAVA_HOME=/Users/kevinye/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home, skip npm plugin, no local HTTP startup. Coordinate before full Maven suite.
- [ ] Self-review + owned-path commit only; report tests and decisions to parent.

### Task 2: Frontend and integration (parent)
- [ ] Add investment-plans.ts types and InvestmentPlansPanel.tsx/scss/test. Read existing accounts, funding accounts and membership APIs; stock search uses TradeStockPicker so CN/HK/US registration resolved as existing flow.
- [ ] Add investments tab plans with URL entry, summary notice linking pending list. Creation/edit dialog split settings/summary; schedule and amount explicit, no automatic price fill. Confirmation dialog actual quantities/price/fee/date and cash preview, disabled invalid/busy controls, explicit paid acknowledgment. Skip/reason, snooze, lifecycle, execution history included.
- [ ] Route notification INVESTMENT_PLAN_OCCURRENCE to /workspace/investments?tab=plans, retain existing single reminder entry.
- [ ] Test due/no-due, errors, immutable snapshot rendering, actual confirmation payload, no quote autofill, setup summary, keyboard dialog, permissions. Run full UI typecheck/test/build.

### Task 3: Review and handoff
- [ ] Independent spec/security/accounting/UI review; fix important issues. Full Java regression excludes StageTwo startup smoke per user preference, Python regression if touched. No push/deployment assertion without evidence.
- [ ] Document actual behavior and any limits; commit only owned changes. Report completion plus not-yet-deployed status.
