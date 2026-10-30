package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.BankAccount;
import com.familyfinance.ledger.BankAccountRepository;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=true", "app.test.source-filter-suite=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionSourceFilterApiTest {
    private static final String TEST_DATABASE_URL = "jdbc:h2:mem:transaction-source-filter-"
            + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final LocalDate OCTOBER = LocalDate.parse("2026-10-12");
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired AppUserRepository users;
    @Autowired HouseholdRepository households;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired FinancialAccountRepository accounts;
    @Autowired BankAccountRepository bankAccounts;
    private final Map<String, Long> singles = new LinkedHashMap<>();
    private final List<FinancialTransaction> batch = new ArrayList<>();
    private HttpClient client;
    private long accountId;
    private long memberId;
    private long categoryId;
    private long sourceId = 800000;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.url", () -> TEST_DATABASE_URL);
        registry.add("spring.flyway.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.flyway.user", () -> "sa");
        registry.add("spring.flyway.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @BeforeAll
    void setUp() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
            Household home = owner.getHousehold();
            FamilyMember member = members.findByHouseholdOrderById(home).get(0);
            FamilyMember otherMember = members.findByHouseholdOrderById(home).get(1);
            Category expense = categories.save(new Category(home, TransactionKind.EXPENSE, "source-fixture-category", "#112233", false, NOW));
            Category otherExpense = categories.save(new Category(home, TransactionKind.EXPENSE, "source-fixture-other-category", "#112233", false, NOW));
            Category income = categories.save(new Category(home, TransactionKind.INCOME, "source-fixture-income", "#112233", false, NOW));
            FinancialAccount account = accounts.save(new FinancialAccount(home, "source-fixture-account", AccountType.CASH, "CNY", 0L));
            FinancialAccount otherAccount = accounts.save(new FinancialAccount(home, "source-fixture-other-account", AccountType.CASH, "CNY", 0L));
            accountId = account.getId(); memberId = member.getId(); categoryId = expense.getId();

            for (String source : List.of("MANUAL", "RECURRING", "LOAN", "LOAN_PAYMENT", "LOAN_PREPAYMENT")) {
                FinancialTransaction row = create(source, home, account, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "source-single-" + source);
                singles.put(source, row.getId());
            }
            for (int i = 0; i < 51; i++) {
                batch.add(create("RECURRING", home, account, owner, member, expense, TransactionKind.EXPENSE,
                        OCTOBER.plusDays(i % 3), "batch-match-" + i));
            }
            create("RECURRING", home, otherAccount, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "batch-decoy-account");
            create("RECURRING", home, account, owner, otherMember, expense, TransactionKind.EXPENSE, OCTOBER, "batch-decoy-member");
            create("RECURRING", home, account, owner, member, otherExpense, TransactionKind.EXPENSE, OCTOBER, "batch-decoy-category");
            create("RECURRING", home, account, owner, member, expense, TransactionKind.EXPENSE, OCTOBER.plusMonths(1), "batch-decoy-month");
            create("RECURRING", home, account, owner, member, income, TransactionKind.INCOME, OCTOBER, "batch-decoy-kind");
            create("RECURRING", home, account, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "different-keyword");
            create("MANUAL", home, account, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "batch-decoy-manual");

            Household outsider = households.save(new Household("synthetic foreign household", NOW));
            AppUser foreignUser = users.save(new AppUser(outsider, "source-foreign", "source-foreign@local.family", "Foreign", "not-a-login-password", NOW));
            FamilyMember foreignMember = members.save(new FamilyMember(outsider, "Foreign member", "member", NOW));
            Category foreignCategory = categories.save(new Category(outsider, TransactionKind.EXPENSE, "Foreign category", "#112233", false, NOW));
            FinancialAccount foreignAccount = accounts.save(new FinancialAccount(outsider, "Foreign account", AccountType.CASH, "CNY", 0L));
            create("RECURRING", outsider, foreignAccount, foreignUser, foreignMember, foreignCategory, TransactionKind.EXPENSE, OCTOBER, "source-single-RECURRING");
            create("RECURRING", outsider, foreignAccount, foreignUser, foreignMember, foreignCategory, TransactionKind.EXPENSE, OCTOBER, "batch-decoy-foreign");
        });
        batch.sort(Comparator.comparing(FinancialTransaction::getOccurredOn).reversed()
                .thenComparing(FinancialTransaction::getId, Comparator.reverseOrder()));
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        JsonNode csrf = json(get("/api/csrf")).path("data");
        HttpRequest login = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=demo&password=demo1234"))
                .build();
        assertThat(send(login).statusCode()).isEqualTo(200);
    }

    private FinancialTransaction create(String source, Household household, FinancialAccount account, AppUser user,
            FamilyMember member, Category category, TransactionKind kind, LocalDate date, String note) {
        FinancialTransaction row = switch (source) {
            case "MANUAL" -> new FinancialTransaction(household, account, user, member, category, kind, 100L, date, null, null, note, NOW, NOW);
            case "LOAN_PAYMENT" -> FinancialTransaction.loanPayment(household, account, user, member, category, 100L, date, ++sourceId, NOW);
            case "LOAN_PREPAYMENT" -> FinancialTransaction.loanPrepayment(household, account, user, member, category, 100L, date, ++sourceId, NOW);
            default -> FinancialTransaction.recurring(household, account, user, member, category, kind, 100L, date, ++sourceId, NOW);
        };
        row.updateDetails(account, member, category, kind, 100L, date, null, null, note, NOW);
        transactions.saveAndFlush(row);
        if (source.equals("LOAN")) {
            // Legacy LOAN has no current factory; create a valid historical row inside the isolated H2 fixture.
            jdbc.update("update financial_transactions set source_type = 'LOAN' where id = ?", row.getId());
        }
        return row;
    }

    @Test
    void datasourceAndFlywayUseTheSameDedicatedInMemoryH2() throws Exception {
        try (var applicationConnection = dataSource.getConnection();
             var migrationConnection = flyway.getConfiguration().getDataSource().getConnection()) {
            String databaseUrl = applicationConnection.getMetaData().getURL();
            assertThat(databaseUrl).startsWith("jdbc:h2:mem:transaction-source-filter-");
            assertThat(migrationConnection.getMetaData().getURL()).isEqualTo(databaseUrl);
            assertThat(applicationConnection.getMetaData().getUserName()).isEqualTo("SA");
            assertThat(migrationConnection.getMetaData().getUserName()).isEqualTo("SA");
        }
    }

    @Test
    void otherCsvFilterValidationAlsoReturnsJsonForCsvClients() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/export.csv?month=invalid"))
                .header("Accept", "text/csv").GET().build();
        HttpResponse<String> response = send(request);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json(response).path("error").path("fields").path("month").asText()).isEqualTo("月份格式必须是 YYYY-MM");
    }

    @Test
    void eachExactSourceMatchesOneHomeRowAcrossListSummaryAndCsv() throws Exception {
        for (var entry : singles.entrySet()) {
            String query = "?month=2026-10&q=source-single&sourceType=" + entry.getKey();
            HttpResponse<String> list = get("/api/transactions" + query);
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.headers().firstValue("X-Total-Elements")).contains("1");
            JsonNode rows = json(list).path("data");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).path("id").asLong()).isEqualTo(entry.getValue());
            assertThat(rows.get(0).path("sourceType").asText()).isEqualTo(entry.getKey());
            JsonNode summary = json(get("/api/transactions/summary" + query)).path("data");
            assertThat(summary.path("transactionCount").asInt()).isEqualTo(1);
            assertThat(summary.path("expense").asText()).isEqualTo("1.00");
            String csv = get("/api/export.csv" + query).body();
            assertThat(csv).contains("source-single-" + entry.getKey());
            assertThat(csv.lines()).hasSize(2);
        }
    }

    @Test
    void absentAndBlankSourceReturnAllHomeSourcesAndNoMatchIsEmpty() throws Exception {
        for (String sourceParam : List.of("", "&sourceType=", "&sourceType=%20%20")) {
            String query = "?month=2026-10&q=source-single" + sourceParam;
            assertThat(json(get("/api/transactions" + query)).path("data")).hasSize(5);
            assertThat(json(get("/api/transactions/summary" + query)).path("data").path("transactionCount").asInt()).isEqualTo(5);
            assertThat(get("/api/export.csv" + query).body().lines()).hasSize(6);
        }
        String query = "?month=2026-10&q=source-single&sourceType=RECURRING&kind=income";
        assertThat(json(get("/api/transactions" + query)).path("data")).isEmpty();
        assertThat(json(get("/api/transactions/summary" + query)).path("data").path("transactionCount").asInt()).isZero();
        assertThat(get("/api/export.csv" + query).body().lines()).hasSize(1);
    }

    @Test
    void sourceAndOtherFiltersIntersectWhileTotalsAndCsvSpanAllPages() throws Exception {
        String query = "?month=2026-10&kind=expense&q=batch-&sourceType=RECURRING&accountId=" + accountId
                + "&memberId=" + memberId + "&categoryId=" + categoryId;
        HttpResponse<String> first = get("/api/transactions" + query + "&page=0&size=50");
        HttpResponse<String> second = get("/api/transactions" + query + "&page=1&size=50");
        assertThat(first.headers().firstValue("X-Total-Elements")).contains("51");
        assertThat(first.headers().firstValue("X-Total-Pages")).contains("2");
        assertThat(first.headers().firstValue("X-Has-Next")).contains("true");
        assertThat(second.headers().firstValue("X-Has-Next")).contains("false");
        assertThat(ids(first)).containsExactlyElementsOf(batch.subList(0, 50).stream().map(FinancialTransaction::getId).toList());
        assertThat(ids(second)).containsExactly(batch.get(50).getId());
        JsonNode summary = json(get("/api/transactions/summary" + query)).path("data");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(51);
        assertThat(summary.path("expense").asText()).isEqualTo("51.00");
        String csv = get("/api/export.csv" + query).body();
        assertThat(csv.lines()).hasSize(52);
        assertThat(csv).contains("batch-match-0", "batch-match-50").doesNotContain("batch-decoy");
    }

    @Test
    void nonblankUnknownOrNonexactSourceIsAFieldErrorForEveryEndpoint() throws Exception {
        for (String endpoint : List.of("/api/transactions", "/api/transactions/summary", "/api/export.csv")) {
            for (String invalid : List.of("manual", " MANUAL ", "NOT_A_SOURCE")) {
                HttpResponse<String> response = get(endpoint + "?sourceType=" + URLEncoder.encode(invalid, StandardCharsets.UTF_8));
                assertThat(response.statusCode()).as(endpoint + " " + invalid).isEqualTo(400);
                JsonNode error = json(response).path("error");
                assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
                assertThat(error.path("fields").path("sourceType").asText()).isEqualTo("来源类型无效");
            }
        }
    }

    @Test
    void sourceIntersectsBankParentAndExplicitDateRange() throws Exception {
        Long bankId = transactionTemplate.execute(status -> {
            AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
            Household home = owner.getHousehold();
            FamilyMember member = members.findById(memberId).orElseThrow();
            Category expense = categories.findById(categoryId).orElseThrow();
            BankAccount bank = bankAccounts.save(new BankAccount(home, "synthetic source bank", "QA bank", "1234"));
            FinancialAccount child = accounts.saveAndFlush(new FinancialAccount(home, "synthetic bank child", AccountType.BANK, "CNY", 0L));
            jdbc.update("update financial_accounts set bank_account_id = ? where id = ?", bank.getId(), child.getId());
            create("RECURRING", home, child, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "bank-source-match");
            create("RECURRING", home, child, owner, member, expense, TransactionKind.EXPENSE, OCTOBER.plusDays(1), "bank-source-outside-range");
            create("MANUAL", home, child, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "bank-source-manual");
            FinancialAccount outside = accounts.findById(accountId).orElseThrow();
            create("RECURRING", home, outside, owner, member, expense, TransactionKind.EXPENSE, OCTOBER, "bank-source-other-account");
            return bank.getId();
        });
        String query = "?from=2026-10-12&to=2026-10-12&sourceType=RECURRING&q=bank-source&bankAccountId=" + bankId;
        JsonNode rows = json(get("/api/transactions" + query)).path("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("note").asText()).isEqualTo("bank-source-match");
        JsonNode summary = json(get("/api/transactions/summary" + query)).path("data");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(1);
        assertThat(summary.path("expense").asText()).isEqualTo("1.00");
        String csv = get("/api/export.csv" + query).body();
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv).contains("bank-source-match").doesNotContain("bank-source-manual", "bank-source-outside-range", "bank-source-other-account");
    }

    @Test
    void invalidCsvSourceIsAJsonFieldErrorWhenClientRequestsCsv() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/export.csv?sourceType=invalid"))
                .header("Accept", "text/csv").GET().build();
        HttpResponse<String> response = send(request);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json(response).path("error").path("fields").path("sourceType").asText()).isEqualTo("来源类型无效");
    }

    private List<Long> ids(HttpResponse<String> response) throws Exception {
        List<Long> ids = new ArrayList<>();
        json(response).path("data").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> get(String path) throws Exception { return send(HttpRequest.newBuilder(uri(path)).GET().build()); }
    private HttpResponse<String> send(HttpRequest request) throws Exception { return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
}
