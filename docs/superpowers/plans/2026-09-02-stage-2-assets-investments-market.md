# Stage 2 Assets, Investments, and A-Share Market Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add household assets, valuation history, A-share investment accounts/trades/positions, and resilient Tushare end-of-day quote synchronization with manual fallback.

**Architecture:** Assets and investments are separate aggregates. Holdings and returns are derived from immutable trade records plus validated price snapshots; a `MarketQuoteProvider` port isolates Tushare from domain logic and allows manual/no-token operation.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring Data JPA, Flyway, Spring RestClient, H2, JUnit 5, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-02-family-finance-stage-2-design.md`

## Global Constraints

- Plans 1 and 2 are prerequisites; only owners/admins mutate shared assets and investments.
- Stock symbols are A-share `######.SH`, `######.SZ`, or `######.BJ` only.
- Trades and valuations are historical facts; derived holdings/returns are never directly client-writable.
- Tushare is read-only, daily-close only, and accessed through HTTPS with `TUSHARE_TOKEN` from the environment.
- Missing/invalid Token never prevents manual asset/investment operation or application startup.
- External responses are validated before persistence and never logged with the Token.

## Plan API Contracts

- `GET|POST /api/assets`
- `PATCH|DELETE /api/assets/{id}`
- `GET|POST /api/assets/{id}/valuations`
- `GET|POST /api/investment-accounts`
- `GET /api/securities/search?q=`
- `GET|POST /api/investment-trades`
- `PATCH|DELETE /api/investment-trades/{id}`
- `POST /api/market-quotes/refresh`
- `POST /api/securities/{id}/manual-price`
- `GET /api/portfolio`

---

### Task 1: V4 asset, investment, and quote migration

**Files:**
- Create: `src/main/resources/db/migration/V4__assets_investments_quotes.sql`
- Create: `src/test/java/com/familyfinance/migration/AssetInvestmentMigrationTest.java`

**Interfaces:**
- Produces tables `assets`, `property_assets`, `vehicle_assets`, `asset_valuations`, `investment_accounts`, `securities`, `investment_trades`, `market_price_snapshots`, and `manual_price_overrides`.

- [ ] **Step 1: Write failing V4 migration test**

```java
@Test
void v4AddsAssetAndInvestmentSchemaWithoutChangingLedger() {
    Path db = migrateStageTwoLedgerFixtureThroughV4();
    assertThat(tables(db)).contains("ASSETS", "INVESTMENT_TRADES", "MARKET_PRICE_SNAPSHOTS");
    assertThat(queryLong(db, "select count(*) from financial_transactions")).isEqualTo(13);
    assertThat(queryLong(db, "select count(*) from household_memberships")).isEqualTo(1);
}
```

- [ ] **Step 2: Run migration test and verify RED**

Run: `./mvnw -q -Dtest=AssetInvestmentMigrationTest test`

- [ ] **Step 3: Implement V4 SQL with exact keys/indexes**

Use household foreign keys on all owned tables, unique security `(market,ts_code)`, unique snapshot `(security_id,trade_date,source)`, decimal quantity/percentage columns with explicit precision, and indexes for household/status/trade-date queries.

- [ ] **Step 4: Run tests and commit**

```bash
./mvnw -q -Dtest=AssetInvestmentMigrationTest test
./mvnw test
git add src/main/resources/db/migration/V4__assets_investments_quotes.sql src/test/java/com/familyfinance/migration/AssetInvestmentMigrationTest.java
git commit -m "feat: add asset investment and quote schema"
```

---

### Task 2: Asset CRUD and valuation history

**Files:**
- Create asset entities/repositories/services/controllers/DTOs under `src/main/java/com/familyfinance/asset`
- Test: `src/test/java/com/familyfinance/asset/AssetApiTest.java`
- Test: `src/test/java/com/familyfinance/asset/AssetValuationServiceTest.java`

**Interfaces:**
- Produces asset CRUD, archive, valuation history, and current-value projection for PROPERTY, VEHICLE, OTHER.
- Cash/bank totals continue to come from financial accounts, not duplicate asset rows.

- [ ] **Step 1: Write failing asset type/permission tests**

```java
@Test
void propertyRequiresAddressAndAreaWhileVehicleRequiresModel() throws Exception {
    createAsset(admin, propertyWithoutArea()).andExpect(status().isUnprocessableEntity());
    createAsset(admin, vehicleWithoutModel()).andExpect(status().isUnprocessableEntity());
    createAsset(member, validProperty()).andExpect(status().isForbidden());
}
```

Cover household/member ownership, purchase/current values, archive behavior, cross-household 404, and inability to delete linked loan assets after Plan 4 adds links.

- [ ] **Step 2: Implement asset aggregate and subtype details**

Use one base entity plus one-to-one subtype details, never JSON fields. Archive retains valuation history. Asset updates cannot rewrite historical valuations.

- [ ] **Step 3: Write failing valuation tests**

Test manual valuation inserts, same-day manual replacement policy, latest value selection by effective date/fetched time, historical order, negative/overflow rejection, and concurrent duplicate unique translation.

- [ ] **Step 4: Implement valuations and update current value atomically**

Insert valuation then update `assets.current_value_cents` in the same transaction only when the new valuation is the latest effective value.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw -q -Dtest=AssetApiTest,AssetValuationServiceTest test
./mvnw test
git add src/main/java/com/familyfinance/asset src/test/java/com/familyfinance/asset
git commit -m "feat: add household assets and valuation history"
```

---

### Task 3: Investment accounts, securities, and trades

**Files:**
- Create entities/repositories/services/controllers/DTOs under `src/main/java/com/familyfinance/investment`
- Create: `src/main/java/com/familyfinance/investment/PositionCalculator.java`
- Create: `src/main/java/com/familyfinance/investment/SecurityService.java`
- Create: `src/main/java/com/familyfinance/investment/SecurityController.java`
- Test: `src/test/java/com/familyfinance/investment/InvestmentTradeApiTest.java`
- Test: `src/test/java/com/familyfinance/investment/PositionCalculatorTest.java`

**Interfaces:**
- Produces investment account CRUD, security search, BUY/SELL/DIVIDEND/FEE trades, positions, realized profit, average cost, and cash impact metadata.

- [ ] **Step 1: Write failing pure position calculations**

```java
@Test
void weightedAverageCostAndPartialSaleAreHandCalculated() {
    List<Trade> trades = List.of(
        buy("100.0000", 1_000L, 100L),
        buy("50.0000", 1_200L, 50L),
        sell("60.0000", 1_500L, 80L));
    Position p = calculator.calculate(trades, 1_600L);
    assertThat(p.quantity()).isEqualByComparingTo("90.0000");
    assertThat(p.costCents()).isEqualTo(96_090L);
    assertThat(p.marketValueCents()).isEqualTo(144_000L);
}
```

Add hand-derived tests for full sale, sale over holding, dividend, fee, multiple decimal quantities, same-day stable ordering, and long overflow.

- [ ] **Step 2: Implement pure calculator then verify GREEN**

Use `BigDecimal` for quantities/prices during calculation and integer cents at storage/output boundaries. Define average-cost method explicitly; do not implement FIFO.

- [ ] **Step 3: Write failing trade API tests**

Test admin-only account/trade mutation, member read access, symbol validation, cross-household resources, insufficient holdings on sell, immutable creator, and response position recalculation.

- [ ] **Step 4: Implement investment services/controllers**

Securities are shared reference rows; accounts/trades are household owned. Trade modification revalidates the complete ordered trade history before flush.

Implement `GET /api/securities/search?q=` against the local security reference table and admin-only resolve/create when entering a valid A-share code/name. Normalize codes to uppercase, enforce `.SH/.SZ/.BJ`, return stable code/name ordering, and test code/name search plus duplicate concurrent creation. Tushare quote refresh may enrich the name later, but registration of a holding never requires a live Token.

- [ ] **Step 5: Run tests and commit**

```bash
./mvnw -q -Dtest=PositionCalculatorTest,InvestmentTradeApiTest test
./mvnw test
git add src/main/java/com/familyfinance/investment src/test/java/com/familyfinance/investment
git commit -m "feat: add A-share investment trades and positions"
```

---

### Task 4: Tushare provider, cache, and manual fallback

**Files:**
- Create: `src/main/java/com/familyfinance/market/MarketQuoteProvider.java`
- Create: `src/main/java/com/familyfinance/market/TushareQuoteProvider.java`
- Create: `src/main/java/com/familyfinance/market/ManualQuoteService.java`
- Create: `src/main/java/com/familyfinance/market/QuoteRefreshService.java`
- Create: `src/main/java/com/familyfinance/market/MarketController.java`
- Create market configuration/DTOs/repositories
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/familyfinance/market/TushareQuoteProviderTest.java`
- Test: `src/test/java/com/familyfinance/market/QuoteRefreshServiceTest.java`
- Test: `src/test/java/com/familyfinance/market/MarketApiTest.java`

**Interfaces:**
- `MarketQuoteProvider.fetchDaily(Set<String> symbols) -> List<DailyQuote>`.
- Tushare calls `https://api.tushare.pro` with `api_name=daily`, token, symbol params, and fixed fields.
- Produces quote refresh/manual endpoints and cached price status for holdings.

- [ ] **Step 1: Write failing Tushare HTTP contract tests**

Use `MockRestServiceServer` with injected RestClient builder. Assert POST body contains token and comma-separated normalized symbols, response `fields/items` maps by field name rather than array position assumptions, and code 2002 becomes `MARKET_PERMISSION_DENIED` without logging token.

```java
server.expect(requestTo("https://api.tushare.pro"))
    .andExpect(method(POST))
    .andExpect(jsonPath("$.api_name").value("daily"))
    .andExpect(jsonPath("$.params.ts_code").value("000001.SZ,600000.SH"));
```

- [ ] **Step 2: Implement provider with validation**

Reject unknown fields, invalid symbol/date, negative price, high<low, and non-finite percent values. Batch all active symbols in one call where response limits permit.

- [ ] **Step 3: Write failing refresh/cache tests**

Test no-token disabled mode, per-household one-minute manual limit, deduplicated symbols, same-day idempotence, latest successful fallback, stale flag, retry only 429/5xx, no retry on permission/token failure, and concurrent refresh collapse.

- [ ] **Step 4: Implement refresh service and manual fallback**

Use a bounded retry policy with injected sleeper/clock. Store provider snapshots and manual overrides separately; effective price picks manual effective date first, then latest Tushare quote.

- [ ] **Step 5: Write API and security tests**

Admin can refresh/set manual price; member can view. Responses show source, tradeDate, fetchedAt, stale, and error state without upstream body/token.

- [ ] **Step 6: Run tests and commit**

```bash
./mvnw -q -Dtest=TushareQuoteProviderTest,QuoteRefreshServiceTest,MarketApiTest test
./mvnw test
git add src/main/java/com/familyfinance/market src/main/resources/application.yml src/test/java/com/familyfinance/market
git commit -m "feat: add resilient Tushare A-share quotes"
```

---

### Task 5: Scheduled close refresh and portfolio reporting

**Files:**
- Create: `src/main/java/com/familyfinance/market/MarketSchedule.java`
- Create: portfolio DTO/service/controller under `reporting`
- Modify: scheduling configuration
- Test: `src/test/java/com/familyfinance/market/MarketScheduleTest.java`
- Test: `src/test/java/com/familyfinance/reporting/PortfolioServiceTest.java`

**Interfaces:**
- Scheduled weekdays at 16:30 Asia/Shanghai; response trade date determines whether a quote is new.
- Portfolio output includes quantity, average cost, close price, market value, realized/unrealized return, source, freshness.

- [ ] **Step 1: Write failing schedule/idempotence tests**

Invoke scheduler service twice with the same injected date; assert one provider request or one stored snapshot set. Return previous trading-date quotes on holidays without creating a fake current-date snapshot.

- [ ] **Step 2: Implement schedule boundary**

The `@Scheduled` method delegates immediately to a service and catches/report failures per household; tests call the service directly.

- [ ] **Step 3: Write failing portfolio tests with literals**

Use known trades and prices to assert market value, cost, realized/unrealized P&L, total return, allocation shares, stale/manual states, and empty portfolio.

- [ ] **Step 4: Implement reporting and run tests**

```bash
./mvnw -q -Dtest=MarketScheduleTest,PortfolioServiceTest test
./mvnw test
```

- [ ] **Step 5: Commit Task 5**

```bash
git add src/main/java/com/familyfinance/market src/main/java/com/familyfinance/reporting src/test/java/com/familyfinance/market src/test/java/com/familyfinance/reporting
git commit -m "feat: add scheduled quotes and portfolio reporting"
```

---

### Task 6: Asset/investment/market acceptance

**Files:**
- Create: `src/test/java/com/familyfinance/acceptance/StageTwoAssetInvestmentSmokeTest.java`
- Create: `docs/acceptance/stage-2-assets-investments-checklist.md`
- Modify: `README.md`

- [ ] **Step 1: Write failing real workflow**

Register owner, create property/valuation, create investment account/security, buy shares, refresh quotes through local stub, verify portfolio/net worth, restart same DB, verify history, then run without Token and set manual price.

- [ ] **Step 2: Run RED and complete integration wiring**

Run: `./mvnw -q -Dtest=StageTwoAssetInvestmentSmokeTest test`

- [ ] **Step 3: Run full gate and commit**

```bash
./mvnw test
git grep -n 'TUSHARE_TOKEN' -- ':!README.md' ':!docs'
git diff --check
git add src/test/java/com/familyfinance/acceptance/StageTwoAssetInvestmentSmokeTest.java docs/acceptance/stage-2-assets-investments-checklist.md README.md
git commit -m "test: certify Stage 2 assets and A-share quotes"
```
