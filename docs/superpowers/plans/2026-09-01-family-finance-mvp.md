# Family Finance Spring Boot MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 17 + Spring Boot 4.1.1 family finance web application with secure login, H2 persistence, household-scoped CRUD, dashboard statistics, rule analysis, CSV export, and a responsive browser UI.

**Architecture:** A Spring Boot MVC monolith serves REST APIs and a static ES-module SPA. Spring Security owns session authentication and CSRF protection, Spring Data JPA owns household-scoped persistence, application services enforce business rules, and pure reporting/UI helpers make calculations independently testable.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Maven Wrapper 3.9.x, Spring Web MVC, Spring Data JPA, Spring Security, Bean Validation, H2, JUnit 5, AssertJ, MockMvc, native HTML/CSS/JavaScript/SVG.

**Spec:** `docs/superpowers/specs/2026-09-01-family-finance-mvp-design.md`

## Global Constraints

- All backend production code uses Java 17 and Spring Boot 4.1.1 under base package `com.familyfinance`.
- Build and run through committed Maven Wrapper files; local Maven installation is not assumed.
- Default run command is `./mvnw spring-boot:run`; default URL is `http://127.0.0.1:8080`.
- Production uses `jdbc:h2:file:./data/family-finance`; tests use a random in-memory H2 database and never touch `./data`.
- Money is stored as positive integer cents and serialized to clients as canonical two-decimal strings.
- Every member, category, transaction, dashboard, analysis, and export operation is scoped by the authenticated principal's `householdId`.
- API success shape is `{"data":...}`; API error shape is `{"error":{"code","message","fields"?}}`.
- Spring Security session login, BCrypt password hashing, SameSite=Lax HttpOnly JSESSIONID, and Cookie CSRF protection are required.
- Seeded local demo login is `demo / demo1234`; only its BCrypt hash is persisted.
- No public registration, roles, budget, asset, OCR, payment sync, external API, or third-party frontend runtime dependency in Sprint 1.
- New behavior follows RED → GREEN → REFACTOR. Each task report includes commands and observed outputs.

---

### Task 1: Spring Boot build, application shell, and environment isolation

**Files:**
- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.mvn/wrapper/maven-wrapper.jar`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `src/main/java/com/familyfinance/FamilyFinanceApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/familyfinance/FamilyFinanceApplicationTest.java`

**Interfaces:**
- Produces a Spring Boot application context and repeatable Maven Wrapper build.
- Production profile binds `server.address=127.0.0.1`, `server.port=8080`, file H2, and `app.seed.enabled=true`.
- Test profile uses `jdbc:h2:mem:family-finance-${random.uuid};DB_CLOSE_DELAY=-1`, `ddl-auto=create-drop`, and `app.seed.enabled=false`.

- [ ] **Step 1: Extract only official build scaffolding**

Download a Spring Initializr archive for Boot 4.1.1, Java 17, Maven, dependencies `web,data-jpa,security,validation,h2` into a temporary directory. Copy only `pom.xml`, `mvnw`, `mvnw.cmd`, and `.mvn/` into the repository; do not copy generated `src/` production code. Add the `spring-security-test` test dependency.

- [ ] **Step 2: Write the failing context test**

```java
package com.familyfinance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class FamilyFinanceApplicationTest {
    @Test
    void applicationContextStarts() {
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run: `./mvnw -q -Dtest=FamilyFinanceApplicationTest test`

Expected: compilation fails because `FamilyFinanceApplication` does not exist.

- [ ] **Step 4: Add the minimal application class and profile configuration**

```java
package com.familyfinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FamilyFinanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FamilyFinanceApplication.class, args);
    }
}
```

Set H2 dialect automatically, `spring.jpa.open-in-view=false`, timezone UTC for JSON, and session cookie HttpOnly/SameSite=Lax. Keep SQL logging off by default.

- [ ] **Step 5: Run Task 1 test and verify GREEN**

Run: `./mvnw -q -Dtest=FamilyFinanceApplicationTest test`

Expected: 1 test passes, 0 failures, 0 errors.

- [ ] **Step 6: Commit Task 1**

```bash
git add pom.xml mvnw mvnw.cmd .mvn src/main/java/com/familyfinance/FamilyFinanceApplication.java src/main/resources/application.yml src/test/resources/application-test.yml src/test/java/com/familyfinance/FamilyFinanceApplicationTest.java
git commit -m "build: bootstrap Spring Boot application"
```

---

### Task 2: Domain schema, deterministic seed, and secure session authentication

**Files:**
- Create: `src/main/java/com/familyfinance/shared/ApiEnvelope.java`
- Create: `src/main/java/com/familyfinance/shared/ApiError.java`
- Create: `src/main/java/com/familyfinance/shared/GlobalExceptionHandler.java`
- Create: `src/main/java/com/familyfinance/household/Household.java`
- Create: `src/main/java/com/familyfinance/household/AppUser.java`
- Create: `src/main/java/com/familyfinance/household/FamilyMember.java`
- Create: `src/main/java/com/familyfinance/category/Category.java`
- Create: `src/main/java/com/familyfinance/category/TransactionKind.java`
- Create: `src/main/java/com/familyfinance/transaction/FinancialTransaction.java`
- Create: repositories beside each aggregate
- Create: `src/main/java/com/familyfinance/auth/FamilyUserPrincipal.java`
- Create: `src/main/java/com/familyfinance/auth/DatabaseUserDetailsService.java`
- Create: `src/main/java/com/familyfinance/auth/AuthController.java`
- Create: `src/main/java/com/familyfinance/config/SecurityConfig.java`
- Create: `src/main/java/com/familyfinance/config/DemoDataInitializer.java`
- Test: `src/test/java/com/familyfinance/config/DemoDataInitializerTest.java`
- Test: `src/test/java/com/familyfinance/auth/AuthenticationApiTest.java`

**Interfaces:**
- Produces JPA entities/tables from the spec and repositories.
- Produces `FamilyUserPrincipal(Long userId, Long householdId, String username, String passwordHash)`.
- Produces `GET /api/csrf`, `GET /api/session`, Spring Security processing at `POST /api/auth/login`, and logout at `POST /api/auth/logout`.
- Produces deterministic demo household, user, five family members, default categories, and June–September 2026 transactions.

- [ ] **Step 1: Write failing seed persistence test**

```java
@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
class DemoDataInitializerTest {
    @Autowired AppUserRepository users;
    @Autowired HouseholdRepository households;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired FinancialTransactionRepository transactions;

    @Test
    void seedIsIdempotentAndStoresOnlyEncodedPassword() throws Exception {
        assertThat(users.count()).isEqualTo(1);
        assertThat(households.count()).isEqualTo(1);
        assertThat(members.count()).isEqualTo(5);
        assertThat(categories.count()).isGreaterThanOrEqualTo(8);
        assertThat(transactions.count()).isGreaterThanOrEqualTo(12);
        AppUser demo = users.findByUsername("demo").orElseThrow();
        assertThat(demo.getPasswordHash()).startsWith("$2");
        assertThat(demo.getPasswordHash()).doesNotContain("demo1234");
    }
}
```

- [ ] **Step 2: Run seed test and verify RED**

Run: `./mvnw -q -Dtest=DemoDataInitializerTest test`

Expected: test compilation fails because domain classes and repositories do not exist.

- [ ] **Step 3: Implement entities, repositories, and deterministic initializer**

Use `GenerationType.IDENTITY`, `Instant` timestamps, `LocalDate` transaction date, unique `app_users.username`, unique category name per household/kind, and positive `amount_cents`. The initializer checks for username `demo` inside a transaction before inserting anything. Use `PasswordEncoder`, never a literal hash.

- [ ] **Step 4: Run seed test and verify GREEN**

Run: `./mvnw -q -Dtest=DemoDataInitializerTest test`

Expected: 1 test passes.

- [ ] **Step 5: Write failing authentication integration tests**

```java
@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class AuthenticationApiTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousApiRequestUsesStandard401Shape() throws Exception {
        mvc.perform(get("/api/session"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
            .andExpect(jsonPath("$.error.message").value("请先登录"));
    }

    @Test
    void demoLoginCreatesSessionAndLogoutInvalidatesIt() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                .with(csrf())
                .param("username", "demo")
                .param("password", "demo1234"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("demo"))
            .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/session").session(session))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(session))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 6: Run auth test and verify RED**

Run: `./mvnw -q -Dtest=AuthenticationApiTest test`

Expected: 401 shape/login assertions fail because security/API configuration is missing.

- [ ] **Step 7: Implement SecurityConfig and auth API**

Configure `CookieCsrfTokenRepository.withHttpOnlyFalse()`, permit static assets, `/api/csrf`, and login, authenticate every other `/api/**`, and return JSON handlers for success/failure/access denied. Use a repository-backed `UserDetailsService`, BCrypt, session fixation protection, maximum one active session disabled (no session registry complexity), and `SessionCreationPolicy.IF_REQUIRED`.

- [ ] **Step 8: Run Task 2 tests and verify GREEN**

Run: `./mvnw -q -Dtest=DemoDataInitializerTest,AuthenticationApiTest test`

Expected: all Task 2 tests pass.

- [ ] **Step 9: Commit Task 2**

```bash
git add src/main/java/com/familyfinance src/test/java/com/familyfinance/config src/test/java/com/familyfinance/auth
git commit -m "feat: add domain seed and secure authentication"
```

---

### Task 3: Household members and categories APIs

**Files:**
- Create: `src/main/java/com/familyfinance/household/MemberService.java`
- Create: `src/main/java/com/familyfinance/household/MemberController.java`
- Create: member request/response DTO records
- Create: `src/main/java/com/familyfinance/category/CategoryService.java`
- Create: `src/main/java/com/familyfinance/category/CategoryController.java`
- Create: category request/response DTO records
- Create: `src/main/java/com/familyfinance/shared/CurrentHousehold.java`
- Modify: member/category/transaction repositories as needed
- Test: `src/test/java/com/familyfinance/household/MemberApiTest.java`
- Test: `src/test/java/com/familyfinance/category/CategoryApiTest.java`

**Interfaces:**
- Produces authenticated `GET|POST /api/members`, `PATCH|DELETE /api/members/{id}`.
- Produces authenticated `GET|POST /api/categories`, `PATCH|DELETE /api/categories/{id}`.
- `CurrentHousehold.id(Authentication) -> long` is the only web-layer path to the current household.

- [ ] **Step 1: Write failing member API tests**

After login with MockMvc, assert listing returns exactly the five seeded members, create trims `"  奶奶  "` to `"奶奶"`, patch changes the role label, and delete returns 204. Create a transaction referencing a seeded member directly through the repository and assert delete returns 409 with `RESOURCE_IN_USE`. Insert a second household/member and assert the demo session receives 404 when addressing its ID.

- [ ] **Step 2: Run member tests and verify RED**

Run: `./mvnw -q -Dtest=MemberApiTest test`

Expected: 404 for missing member routes.

- [ ] **Step 3: Implement household-scoped member service and controller**

Validate name length 1–30 and role label length 0–30. Repository update/delete lookups use `id AND householdId`. Convert `DataIntegrityViolationException` or an explicit reference count into 409 `RESOURCE_IN_USE`.

- [ ] **Step 4: Run member tests and verify GREEN**

Run: `./mvnw -q -Dtest=MemberApiTest test`

Expected: all member tests pass.

- [ ] **Step 5: Write failing category API tests**

Assert income/expense listing, create with `kind:"expense"`, invalid `kind:"transfer"` returns field error, invalid color returns field error, cross-household ID returns 404, and referenced category deletion returns 409.

- [ ] **Step 6: Run category tests and verify RED**

Run: `./mvnw -q -Dtest=CategoryApiTest test`

Expected: 404 for missing category routes.

- [ ] **Step 7: Implement category service and controller**

Use enum values `income` and `expense` in JSON via `@JsonValue/@JsonCreator`. Validate name 1–30, color `#[0-9A-Fa-f]{6}`, and uniqueness within household/kind. Default categories may be renamed but not changed to another kind when referenced.

- [ ] **Step 8: Run Task 3 tests and verify GREEN**

Run: `./mvnw -q -Dtest=MemberApiTest,CategoryApiTest test`

Expected: all Task 3 tests pass.

- [ ] **Step 9: Commit Task 3**

```bash
git add src/main/java/com/familyfinance/household src/main/java/com/familyfinance/category src/main/java/com/familyfinance/shared src/test/java/com/familyfinance/household src/test/java/com/familyfinance/category
git commit -m "feat: add family member and category APIs"
```

---

### Task 4: Transaction CRUD, filters, and CSV export

**Files:**
- Create: `src/main/java/com/familyfinance/transaction/TransactionService.java`
- Create: `src/main/java/com/familyfinance/transaction/TransactionController.java`
- Create: transaction request/response/filter DTOs
- Create: `src/main/java/com/familyfinance/shared/Money.java`
- Create: `src/main/java/com/familyfinance/reporting/CsvExportService.java`
- Create: `src/main/java/com/familyfinance/reporting/ExportController.java`
- Modify: `FinancialTransactionRepository.java`
- Test: `src/test/java/com/familyfinance/transaction/TransactionApiTest.java`
- Test: `src/test/java/com/familyfinance/reporting/CsvExportApiTest.java`

**Interfaces:**
- Produces `GET|POST /api/transactions`, `GET|PATCH|DELETE /api/transactions/{id}`.
- Filter query: `month, from, to, kind, memberId, categoryId, q`.
- Response amount is a two-decimal string; request amount accepts a decimal string with at most two fractional digits.
- Produces `GET /api/export.csv` using the identical filter object.

- [ ] **Step 1: Write failing money and transaction API tests**

Create `MoneyTest` first with literals: `"12.30" -> 1230L`, `1230L -> "12.30"`, reject zero, negative, exponent, more than two decimals, and values over 999,999,999.99.

Then write MockMvc integration tests that:

- create an expense `12.30` and verify repository stores `1230`;
- reject mismatched category kind;
- patch merchant/note and preserve other fields;
- filter September expenses by keyword;
- return 404 for another household's transaction;
- delete and confirm absence.

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -q -Dtest=MoneyTest,TransactionApiTest test`

Expected: compilation fails because money and transaction API classes are missing.

- [ ] **Step 3: Implement money utility, JPA Specification filters, service, and controller**

Use string parsing for cents rather than `double`. Validate ISO dates, free-text limits merchant/location 100 and note 500, member/category household ownership, and category-kind match. The repository extends `JpaSpecificationExecutor<FinancialTransaction>`; every specification begins with household equality and binds values.

- [ ] **Step 4: Run transaction tests and verify GREEN**

Run: `./mvnw -q -Dtest=MoneyTest,TransactionApiTest test`

Expected: all transaction tests pass.

- [ ] **Step 5: Write failing CSV API test**

Authenticate and request `/api/export.csv?month=2026-09&kind=expense`. Assert content type `text/csv;charset=UTF-8`, UTF-8 BOM, header `日期,类型,金额,成员,分类,商家,地点,备注`, Chinese expense rows, absence of income rows, and RFC-style quote escaping.

- [ ] **Step 6: Run CSV test and verify RED**

Run: `./mvnw -q -Dtest=CsvExportApiTest test`

Expected: 404 for export route.

- [ ] **Step 7: Implement CSV export using the same service filter**

Never duplicate query rules. Escape quote/comma/CR/LF fields and return `Content-Disposition: attachment; filename="family-finance.csv"`.

- [ ] **Step 8: Run Task 4 tests and verify GREEN**

Run: `./mvnw -q -Dtest=MoneyTest,TransactionApiTest,CsvExportApiTest test`

Expected: all Task 4 tests pass.

- [ ] **Step 9: Commit Task 4**

```bash
git add src/main/java/com/familyfinance/transaction src/main/java/com/familyfinance/reporting src/main/java/com/familyfinance/shared/Money.java src/test/java/com/familyfinance/transaction src/test/java/com/familyfinance/reporting
git commit -m "feat: add transaction workflows and CSV export"
```

---

### Task 5: Dashboard statistics and rule-based analysis

**Files:**
- Create: `src/main/java/com/familyfinance/reporting/DashboardService.java`
- Create: `src/main/java/com/familyfinance/reporting/AnalysisService.java`
- Create: reporting DTO records
- Create: `src/main/java/com/familyfinance/reporting/ReportingController.java`
- Modify: `FinancialTransactionRepository.java`
- Test: `src/test/java/com/familyfinance/reporting/DashboardServiceTest.java`
- Test: `src/test/java/com/familyfinance/reporting/AnalysisServiceTest.java`
- Test: `src/test/java/com/familyfinance/reporting/ReportingApiTest.java`

**Interfaces:**
- Produces `GET /api/dashboard?month=YYYY-MM`.
- Produces `GET /api/analysis?month=YYYY-MM`.
- Dashboard response contains `summary`, `daily`, `expenseByCategory`, and `expenseByMember`.
- Analysis response contains at most three ordered insights plus `historyStatus`.

- [ ] **Step 1: Write failing dashboard service test with hand-calculated values**

Persist three controlled transactions: income 500000 cents, expenses 12000 and 8000 cents. Assert summary strings `5000.00`, `200.00`, `4800.00`; daily trend in date order; category shares 60.0 and 40.0; member totals descending. Use a test household, never seed-derived computed expectations.

- [ ] **Step 2: Run dashboard test and verify RED**

Run: `./mvnw -q -Dtest=DashboardServiceTest test`

Expected: compilation fails because DashboardService is missing.

- [ ] **Step 3: Implement dashboard aggregation**

Use repository reads scoped by household/month, accumulate integer cents, and convert only at DTO boundary. Sort daily dates ascending and category/member totals descending with stable ID tie-breaks.

- [ ] **Step 4: Run dashboard test and verify GREEN**

Run: `./mvnw -q -Dtest=DashboardServiceTest test`

Expected: dashboard test passes.

- [ ] **Step 5: Write failing analysis service tests**

Assert current 150000 cents against history 100000/90000/110000 yields `MONTHLY_INCREASE` metric `50.0%`. Assert top category and largest expense insights. Assert fewer than two historical months yields `historyStatus:"insufficient"` and no monthly comparison.

- [ ] **Step 6: Run analysis tests and verify RED**

Run: `./mvnw -q -Dtest=AnalysisServiceTest test`

Expected: compilation fails because AnalysisService is missing.

- [ ] **Step 7: Implement analysis rules**

Return no more than three insights in order: monthly comparison, top category, largest single expense. Do not emit an insight for zero/current-empty data. Use `BigDecimal` only for percentage division and round one decimal HALF_UP.

- [ ] **Step 8: Write and satisfy reporting API tests**

MockMvc tests authenticate, request September 2026, assert known deterministic seed totals, reject `month=2026-13` with field error, and request an empty month without fabricated conclusions.

Run RED: `./mvnw -q -Dtest=ReportingApiTest test` before the controller exists.

Implement the controller and run GREEN:
`./mvnw -q -Dtest=DashboardServiceTest,AnalysisServiceTest,ReportingApiTest test`.

- [ ] **Step 9: Commit Task 5**

```bash
git add src/main/java/com/familyfinance/reporting src/main/java/com/familyfinance/transaction/FinancialTransactionRepository.java src/test/java/com/familyfinance/reporting
git commit -m "feat: add household statistics and analysis"
```

---

### Task 6: Responsive SPA, full-system acceptance, and handoff

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/styles.css`
- Create: `src/main/resources/static/ui-state.js`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/java/com/familyfinance/config/SpaRoutingConfig.java`
- Test: `src/test/java/com/familyfinance/web/StaticApplicationTest.java`
- Test: `src/test/java/com/familyfinance/acceptance/SprintOneSmokeTest.java`
- Create: `README.md`
- Create: `docs/acceptance/sprint-1-checklist.md`

**Interfaces:**
- Consumes all REST APIs from Tasks 2–5.
- Produces browser views `dashboard`, `transactions`, `analysis`, and `settings`.
- Produces real smoke workflow covering login, CRUD, filter, dashboard, analysis, export, logout, and persistence.

- [ ] **Step 1: Write failing static application test**

With MockMvc, assert `GET /` returns HTML, `GET /styles.css` returns CSS, `GET /app.js` returns JavaScript, an extensionless route `/transactions` forwards to the SPA, and `/api/unknown` remains JSON 404.

- [ ] **Step 2: Run static test and verify RED**

Run: `./mvnw -q -Dtest=StaticApplicationTest test`

Expected: missing static resources/SPA route fail.

- [ ] **Step 3: Build the browser UI**

Use exact visual tokens from the spec. Provide:

- login panel showing the local demo account;
- ledger-style navigation and month selector;
- income/expense/balance summary;
- accessible SVG cashflow ledger track;
- category/member bars;
- analysis insight list;
- filterable transaction table/cards;
- create/edit modal with member/category choices;
- member and category settings;
- CSV download and logout;
- role=status messages, labeled inputs, Escape modal close, visible focus, reduced motion, and responsive 390/1440 layouts.

All write requests first load `/api/csrf` and send `X-XSRF-TOKEN`. After writes, refetch authoritative server state.

- [ ] **Step 4: Implement SPA routing and satisfy static test**

Forward only extensionless non-API routes to `index.html`; preserve Spring's API 404 JSON. Run `./mvnw -q -Dtest=StaticApplicationTest test` and expect GREEN.

- [ ] **Step 5: Write failing full smoke test**

Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a temporary file H2 URL. Drive real HTTP with `java.net.http.HttpClient` and CookieManager:

1. get CSRF and login;
2. list members/categories;
3. create an 88.60 expense on 2026-09-10;
4. find it with month/kind/keyword filters;
5. verify dashboard expense increases exactly 88.60;
6. verify analysis has an insight array;
7. export CSV and find the row;
8. verify logout blocks session.

The test must fail before any integration corrections.

- [ ] **Step 6: Make minimal corrections and run the complete suite**

Run: `./mvnw test`

Expected: all tests pass, 0 failures, 0 errors. Keep Surefire output free of stack traces and unexpected warnings.

- [ ] **Step 7: Write README and acceptance checklist**

README includes Java 17 prerequisite, `./mvnw spring-boot:run`, local URL, demo account, H2 data path, test command, safe reset by moving the database aside, project structure, API summary, and Sprint exclusions. Checklist maps all nine spec acceptance criteria to an automated test or browser action.

- [ ] **Step 8: Run real browser acceptance**

Use Playwright CLI against the running application at 1440×900 and 390×844. Verify login, dashboard, create/edit/filter/delete transaction, analysis, settings, CSV request, logout, keyboard focus, no horizontal overflow, and no uncaught console errors. Capture screenshots under `output/playwright/` as QA evidence only.

- [ ] **Step 9: Verify file-database restart persistence**

Create a uniquely named transaction in the browser, stop and restart Spring Boot using the same file H2 database, log in, and verify the row remains. Delete only that test row through the UI.

- [ ] **Step 10: Commit Task 6**

```bash
git add src/main/resources/static src/main/java/com/familyfinance/config/SpaRoutingConfig.java src/test/java/com/familyfinance/web src/test/java/com/familyfinance/acceptance README.md docs/acceptance/sprint-1-checklist.md
git commit -m "feat: deliver Spring Boot Sprint 1 application"
```

