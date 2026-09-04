package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.FamilyFinanceApplication;
import com.familyfinance.reporting.NetWorthSnapshotService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Product acceptance: real HTTP/CSRF plus a full close and restart on the same file H2 database. */
class StageTwoLoanReportingSmokeTest {
    private static final String EMAIL = "stage-two-loans-owner@example.com";
    private static final String PASSWORD = "stage-two-loans-pass-2026";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

    @TempDir Path tempDir;

    @Test
    void linkedLoanRemindersReportingAndSnapshotRemainIdempotentAfterRestart() throws Exception {
        Path database = tempDir.resolve("stage-two-loans-reporting").toAbsolutePath();
        State state;
        try (RunningApplication first = start(database)) {
            Api anonymous = first.api();
            assertThat(anonymous.write("POST", "/api/auth/register", """
                    {"email":"%s","displayName":"贷款验收所有者","password":"%s",
                     "mode":"CREATE","householdName":"贷款报表验收家庭"}
                    """.formatted(EMAIL, PASSWORD)).statusCode()).isEqualTo(201);
            Api owner = first.api();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            JsonNode session = owner.data(owner.get("/api/session"));
            long householdId = session.path("householdId").asLong();
            long userId = session.path("userId").asLong();
            long memberId = owner.data(owner.get("/api/members")).get(0).path("id").asLong();
            long expenseCategoryId = owner.data(owner.expect(owner.write("POST", "/api/categories", """
                    {"kind":"EXPENSE","name":"贷款验收分类","color":"#3370FF"}
                    """), 201)).path("id").asLong();

            JsonNode account = owner.data(owner.expect(owner.write("POST", "/api/accounts", """
                    {"name":"还款验收账户","type":"BANK","currency":"CNY","openingBalance":"10000.00"}
                    """), 201));
            long accountId = account.path("id").asLong();
            JsonNode property = owner.data(owner.expect(owner.write("POST", "/api/assets", """
                    {"name":"贷款抵押房产","type":"PROPERTY","acquiredOn":"2020-01-01",
                     "purchaseValue":"9000.00","currentValue":"9000.00",
                     "property":{"address":"杭州验收路 1 号","areaSqm":"80.00","usageType":"SELF_USE"}}
                    """), 201));
            long assetId = property.path("id").asLong();
            JsonNode loan = owner.data(owner.expect(owner.write("POST", "/api/loans", """
                    {"name":"验收房贷","type":"MORTGAGE","linkedAssetId":%d,"memberId":%d,"assignedUserId":%d,
                     "paymentAccountId":%d,"paymentCategoryId":%d,"principal":"4000.00","annualRate":0.000000,
                     "termMonths":1,"repaymentMethod":"EQUAL_PRINCIPAL","startOn":"2026-08-01"}
                    """.formatted(assetId, memberId, userId, accountId, expenseCategoryId)), 201));
            long loanId = loan.path("id").asLong();
            JsonNode schedule = owner.data(owner.get("/api/loans/" + loanId + "/schedule"));
            assertThat(schedule).hasSize(1);
            long installmentId = schedule.get(0).path("id").asLong();
            assertThat(schedule.get(0).path("principal").asString()).isEqualTo("4000.00");

            owner.data(owner.expect(owner.write("POST", "/api/notifications/generate", null), 200));
            assertThat(owner.data(owner.get("/api/notifications")).path("items").toString()).contains("LOAN_DUE");
            owner.data(owner.expect(owner.write("POST", "/api/budgets", """
                    {"periodMonth":"2026-09","scopeType":"TOTAL","amount":"10.00"}
                    """), 201));

            JsonNode confirmed = owner.data(owner.expect(owner.write("POST", "/api/loan-installments/" + installmentId + "/confirm", null), 200));
            long transactionId = confirmed.path("confirmedTransactionId").asLong();
            assertThat(owner.data(owner.expect(owner.write("POST", "/api/loan-installments/" + installmentId + "/confirm", null), 200))
                    .path("confirmedTransactionId").asLong()).isEqualTo(transactionId);
            assertThat(owner.data(owner.get("/api/loans/" + loanId)).path("currentPrincipal").asString()).isEqualTo("0.00");
            assertThat(owner.data(owner.get("/api/transactions")).toString()).contains("贷款还款");
            assertThat(owner.data(owner.get("/api/net-worth")).path("netWorth").asString()).isEqualTo("15000.00");
            assertThat(owner.data(owner.get("/api/debt-analysis")).path("liability").asString()).isEqualTo("0.00");

            owner.data(owner.expect(owner.write("POST", "/api/notifications/generate", null), 200));
            assertThat(owner.data(owner.get("/api/notifications")).path("items").toString()).contains("BUDGET_LIMIT");
            AcceptanceClock.set(Instant.parse("2026-10-05T02:00:00Z"));
            owner.data(owner.expect(owner.write("POST", "/api/notifications/generate", null), 200));
            String notifications = owner.data(owner.get("/api/notifications")).path("items").toString();
            assertThat(notifications).contains("BUDGET_LIMIT", "ASSET_VALUATION_STALE");
            first.context().getBean(NetWorthSnapshotService.class).generate(householdId, TODAY);
            first.context().getBean(NetWorthSnapshotService.class).generate(householdId, TODAY);
            assertThat(owner.data(owner.get("/api/net-worth")).path("history")).hasSize(2);
            state = new State(loanId, installmentId, transactionId);
        }

        try (RunningApplication restarted = start(database)) {
            Api owner = restarted.api();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            assertThat(owner.data(owner.get("/api/loans/" + state.loanId())).path("currentPrincipal").asString()).isEqualTo("0.00");
            assertThat(owner.data(owner.get("/api/loans/" + state.loanId() + "/schedule")).get(0)
                    .path("confirmedTransactionId").asLong()).isEqualTo(state.transactionId());
            assertThat(owner.data(owner.get("/api/net-worth")).path("history")).hasSize(2);
            assertThat(owner.data(owner.get("/api/notifications")).path("items").toString()).contains("BUDGET_LIMIT");
        }
    }

    private RunningApplication start(Path database) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(FamilyFinanceApplication.class, FixedClockConfiguration.class)
                .run("--server.port=0", "--spring.datasource.url=jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.seed.enabled=false", "--app.scheduling.enabled=false", "--TUSHARE_TOKEN=", "--spring.main.banner-mode=off");
        return new RunningApplication(context, ((WebServerApplicationContext) context).getWebServer().getPort(), context.getBean(ObjectMapper.class));
    }

    private record State(long loanId, long installmentId, long transactionId) { }
    private record RunningApplication(ConfigurableApplicationContext context, int port, ObjectMapper mapper) implements AutoCloseable {
        Api api() { return new Api(port, mapper); }
        @Override public void close() { context.close(); }
    }
    private static final class Api {
        private final int port; private final ObjectMapper mapper; private final HttpClient client;
        Api(int port, ObjectMapper mapper) { this.port = port; this.mapper = mapper; client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
        HttpResponse<String> get(String path) throws Exception { return send(HttpRequest.newBuilder(uri(path)).GET().build()); }
        HttpResponse<String> login(String username, String password) throws Exception { Csrf csrf = csrf(); String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8); return send(HttpRequest.newBuilder(uri("/api/auth/login")).header(csrf.header(), csrf.token()).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()); }
        HttpResponse<String> write(String method, String path, String body) throws Exception { Csrf csrf = csrf(); HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header(csrf.header(), csrf.token()); if (body != null) request.header("Content-Type", "application/json"); return send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build()); }
        HttpResponse<String> expect(HttpResponse<String> response, int status) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); return response; }
        JsonNode data(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isBetween(200, 299); return mapper.readTree(response.body()).path("data"); }
        private HttpResponse<String> send(HttpRequest request) throws Exception { return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
        private Csrf csrf() throws Exception { JsonNode csrf = data(get("/api/csrf")); return new Csrf(csrf.path("headerName").asString(), csrf.path("token").asString()); }
    }
    private record Csrf(String header, String token) { }
    @Configuration(proxyBeanMethods = false) static class FixedClockConfiguration {
        @Bean @Primary Clock stageTwoLoanClock() { AcceptanceClock.set(Instant.parse("2026-09-03T02:00:00Z")); return AcceptanceClock.INSTANCE; }
    }
    static final class AcceptanceClock extends Clock {
        static final AcceptanceClock INSTANCE = new AcceptanceClock();
        private static final AtomicReference<Instant> NOW = new AtomicReference<>();
        static void set(Instant instant) { NOW.set(instant); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW.get(); }
    }
}
