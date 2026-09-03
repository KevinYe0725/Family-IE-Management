package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.FamilyFinanceApplication;
import com.familyfinance.market.DailyQuote;
import com.familyfinance.market.MarketQuoteProvider;
import java.math.BigDecimal;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Product-level Stage 2 acceptance: real random-port HTTP, cookie/CSRF, file H2 restart,
 * a test-only quote port, then genuine no-token fallback.  This test never calls Tushare.
 */
class StageTwoAssetInvestmentSmokeTest {

    private static final String EMAIL = "stage-two-assets-owner@example.com";
    private static final String PASSWORD = "stage-two-assets-pass-2026";
    private static final String TRADE_DATE = "2026-09-01";
    private static final String TODAY = "2026-09-03";

    @TempDir
    Path tempDir;

    @Test
    void realHttpAssetsInvestmentsQuotesAndManualFallbackSurviveRestart() throws Exception {
        Path database = tempDir.resolve("stage-two-assets-investments").toAbsolutePath();
        State state;
        try (RunningApplication first = start(database, true)) {
            Api anonymous = first.newApi();
            assertThat(anonymous.write("POST", "/api/auth/register", """
                    {"email":"%s","displayName":"资产投资所有者","password":"%s",
                     "mode":"CREATE","householdName":"资产投资验收家庭"}
                    """.formatted(EMAIL, PASSWORD)).statusCode()).isEqualTo(201);

            Api owner = first.newApi();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            JsonNode session = owner.data(owner.get("/api/session"));
            assertThat(session.path("role").asString()).isEqualTo("OWNER");

            JsonNode property = owner.data(owner.expectStatus(owner.write("POST", "/api/assets", """
                    {"name":"验收房产","type":"PROPERTY","acquiredOn":"2020-06-01",
                     "purchaseValue":"1000000.00","currentValue":"1200000.00",
                     "property":{"address":"杭州验收路 8 号","areaSqm":"89.50","usageType":"SELF_USE"}}
                    """), 201));
            long assetId = property.path("id").asLong();
            assertAsset(property, assetId, "1200000.00");
            JsonNode valuation = owner.data(owner.expectStatus(owner.write(
                    "POST", "/api/assets/" + assetId + "/valuations", """
                    {"valuedOn":"2026-09-03","value":"1250000.00","note":"验收估值"}
                    """), 201));
            long valuationId = valuation.path("id").asLong();
            assertThat(valuation.path("source").asString()).isEqualTo("MANUAL");
            assertThat(valuation.path("value").asString()).isEqualTo("1250000.00");
            assertAsset(owner.data(owner.get("/api/assets/" + assetId)), assetId, "1250000.00");

            JsonNode account = owner.data(owner.expectStatus(owner.write("POST", "/api/investment-accounts", """
                    {"name":"验收证券账户","brokerName":"本地券商","currency":"CNY"}
                    """), 201));
            long accountId = account.path("id").asLong();
            JsonNode security = owner.data(owner.expectStatus(owner.write("POST", "/api/securities/resolve", """
                    {"tsCode":"600000.SH","name":"浦发银行"}
                    """), 200));
            long securityId = security.path("id").asLong();
            JsonNode created = owner.data(owner.expectStatus(owner.write("POST", "/api/investment-trades", """
                    {"accountId":%d,"securityId":%d,"type":"BUY","quantity":"100.0000",
                     "price":"10.00","fee":"1.00","tradedOn":"%s"}
                    """.formatted(accountId, securityId, TRADE_DATE)), 201));
            long tradeId = created.path("trade").path("id").asLong();
            assertThat(new BigDecimal(created.path("position").path("quantity").asString()))
                    .isEqualByComparingTo("100.0000");
            assertThat(created.path("position").path("cost").asString()).isEqualTo("1001.00");

            JsonNode refreshed = owner.data(owner.expectStatus(
                    owner.write("POST", "/api/market-quotes/refresh", null), 200));
            assertThat(refreshed.path("state").asString()).isEqualTo("READY");
            assertThat(refreshed.path("refreshed").asInt()).isEqualTo(1);
            assertQuote(refreshed.path("quotes").get(0), securityId, "TUSHARE", "12.00");
            assertThat(first.context().getBean(AcceptanceMarketStub.class).calls()).isEqualTo(1);
            assertPortfolio(owner, accountId, securityId, "TUSHARE", "12.00", "1200.00", "199.00");

            assertThat(owner.data(owner.get("/api/assets/" + assetId + "/valuations")).path("items")).hasSize(1);
            assertThat(owner.data(owner.get("/api/investment-trades?accountId=" + accountId)).path("items")).hasSize(1);
            state = new State(assetId, valuationId, accountId, securityId, tradeId);
        }

        // The second process has no primary stub and an explicitly blank token: persisted data remains usable.
        try (RunningApplication restarted = start(database, false)) {
            Api owner = restarted.newApi();
            assertThat(owner.login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
            assertThat(owner.data(owner.get("/api/session")).path("email").asString()).isEqualTo(EMAIL);
            assertAsset(owner.data(owner.get("/api/assets/" + state.assetId())), state.assetId(), "1250000.00");
            JsonNode valuations = owner.data(owner.get("/api/assets/" + state.assetId() + "/valuations")).path("items");
            assertThat(valuations).hasSize(1);
            assertThat(valuations.get(0).path("id").asLong()).isEqualTo(state.valuationId());
            assertThat(valuations.get(0).path("value").asString()).isEqualTo("1250000.00");
            JsonNode trade = owner.data(owner.get("/api/investment-trades/" + state.tradeId()));
            assertThat(trade.path("accountId").asLong()).isEqualTo(state.accountId());
            assertThat(trade.path("security").path("id").asLong()).isEqualTo(state.securityId());
            assertThat(trade.path("type").asString()).isEqualTo("BUY");
            assertThat(new BigDecimal(trade.path("quantity").asString())).isEqualByComparingTo("100.0000");
            assertPortfolio(owner, state.accountId(), state.securityId(), "TUSHARE", "12.00", "1200.00", "199.00");

            JsonNode disabled = owner.data(owner.expectStatus(
                    owner.write("POST", "/api/market-quotes/refresh", null), 200));
            assertThat(disabled.path("state").asString()).isEqualTo("DISABLED");
            assertThat(disabled.path("error").asString()).isEqualTo("MARKET_DISABLED");
            JsonNode manual = owner.data(owner.expectStatus(owner.write(
                    "POST", "/api/securities/" + state.securityId() + "/manual-price", """
                    {"price":"13.00","effectiveOn":"2026-09-03","note":"无 Token 本地收盘价"}
                    """), 201));
            assertQuote(manual, state.securityId(), "MANUAL", "13.00");
            assertQuote(owner.data(owner.get("/api/market-quotes")).get(0), state.securityId(), "MANUAL", "13.00");
            assertPortfolio(owner, state.accountId(), state.securityId(), "MANUAL", "13.00", "1300.00", "299.00");
        }
    }

    private RunningApplication start(Path database, boolean stubQuotes) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                FamilyFinanceApplication.class, FixedClockConfiguration.class, LocalStubMarketConfiguration.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + h2Url(database),
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.seed.enabled=false",
                        "--app.scheduling.enabled=false",
                        "--TUSHARE_TOKEN=",
                        "--acceptance.market.stub.enabled=" + stubQuotes,
                        "--spring.main.banner-mode=off");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new RunningApplication(context, port, context.getBean(ObjectMapper.class));
    }

    private static void assertAsset(JsonNode asset, long id, String currentValue) {
        assertThat(asset.path("id").asLong()).isEqualTo(id);
        assertThat(asset.path("type").asString()).isEqualTo("PROPERTY");
        assertThat(asset.path("currentValue").asString()).isEqualTo(currentValue);
        assertThat(asset.path("property").path("address").asString()).isEqualTo("杭州验收路 8 号");
    }

    private static void assertQuote(JsonNode quote, long securityId, String source, String price) {
        assertThat(quote.path("securityId").asLong()).isEqualTo(securityId);
        assertThat(quote.path("tsCode").asString()).isEqualTo("600000.SH");
        assertThat(quote.path("price").asString()).isEqualTo(price);
        assertThat(quote.path("source").asString()).isEqualTo(source);
        assertThat(quote.path("tradeDate").asString()).isEqualTo(TODAY);
        assertThat(quote.path("stale").asBoolean()).isFalse();
    }

    private static void assertPortfolio(
            Api api, long accountId, long securityId, String source, String price, String marketValue, String totalProfit)
            throws Exception {
        JsonNode portfolio = api.data(api.get("/api/portfolio"));
        assertThat(portfolio.path("positions")).hasSize(1);
        JsonNode position = portfolio.path("positions").get(0);
        assertThat(position.path("accountId").asLong()).isEqualTo(accountId);
        assertThat(position.path("securityId").asLong()).isEqualTo(securityId);
        assertThat(new BigDecimal(position.path("quantity").asString())).isEqualByComparingTo("100.0000");
        assertThat(position.path("cost").asString()).isEqualTo("1001.00");
        assertThat(position.path("price").asString()).isEqualTo(price);
        assertThat(position.path("marketValue").asString()).isEqualTo(marketValue);
        assertThat(position.path("totalProfit").asString()).isEqualTo(totalProfit);
        assertThat(position.path("source").asString()).isEqualTo(source);
        assertThat(portfolio.path("totals").path("cost").asString()).isEqualTo("1001.00");
        assertThat(portfolio.path("totals").path("marketValue").asString()).isEqualTo(marketValue);
    }

    private static String h2Url(Path database) {
        return "jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private record State(long assetId, long valuationId, long accountId, long securityId, long tradeId) { }

    private record RunningApplication(ConfigurableApplicationContext context, int port, ObjectMapper objectMapper)
            implements AutoCloseable {
        Api newApi() { return new Api(port, objectMapper); }
        @Override public void close() { context.close(); }
    }

    private static final class Api {
        private final int port;
        private final ObjectMapper objectMapper;
        private final HttpClient client;

        private Api(int port, ObjectMapper objectMapper) {
            this.port = port;
            this.objectMapper = objectMapper;
            this.client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET().build());
        }

        HttpResponse<String> login(String username, String password) throws Exception {
            String form = Map.of("username", username, "password", password).entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .reduce((left, right) -> left + "&" + right).orElseThrow();
            Csrf csrf = csrf();
            return send(HttpRequest.newBuilder(uri("/api/auth/login")).header(csrf.header(), csrf.token())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        }

        HttpResponse<String> write(String method, String path, String body) throws Exception {
            Csrf csrf = csrf();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header(csrf.header(), csrf.token());
            if (body != null) request.header("Content-Type", "application/json");
            return send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build());
        }

        HttpResponse<String> expectStatus(HttpResponse<String> response, int expected) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
            return response;
        }

        JsonNode data(HttpResponse<String> response) throws Exception {
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
            return objectMapper.readTree(response.body()).path("data");
        }

        HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

        private Csrf csrf() throws Exception {
            JsonNode csrf = data(get("/api/csrf"));
            return new Csrf(csrf.path("headerName").asString(), csrf.path("token").asString());
        }
    }

    private record Csrf(String header, String token) { }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean @Primary
        Clock stageTwoAssetClock() {
            return Clock.fixed(Instant.parse("2026-09-03T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalStubMarketConfiguration {
        @Bean @Primary
        @ConditionalOnProperty(name = "acceptance.market.stub.enabled", havingValue = "true")
        AcceptanceMarketStub acceptanceMarketQuoteProvider() {
            return new AcceptanceMarketStub();
        }
    }

    static class AcceptanceMarketStub implements MarketQuoteProvider {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public List<DailyQuote> fetchDaily(Set<String> symbols) {
            assertThat(symbols).containsExactly("600000.SH");
            calls.incrementAndGet();
            return List.of(new DailyQuote("600000.SH", LocalDate.parse(TODAY),
                    1180, 1220, 1170, 1200, 1190, new BigDecimal("0.84")));
        }
        int calls() { return calls.get(); }
    }
}
