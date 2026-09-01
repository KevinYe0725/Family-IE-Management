package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.seed.enabled=true",
            "spring.datasource.url=jdbc:h2:file:./target/sprint-one-smoke-${random.uuid};DB_CLOSE_ON_EXIT=FALSE",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class SprintOneSmokeTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    private HttpClient client;
    private String csrfHeader;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        loadCsrf();
    }

    private void loadCsrf() throws Exception {
        JsonNode csrf = json(get("/api/csrf")).path("data");
        csrfHeader = csrf.path("headerName").asString();
        csrfToken = csrf.path("token").asString();
    }

    @Test
    void authenticatedHouseholdWorkflowCreatesFiltersReportsExportsAndLogsOut() throws Exception {
        assertThat(postForm("/api/auth/login", Map.of("username", "demo", "password", "demo1234"))
                .statusCode()).isEqualTo(200);

        JsonNode members = json(get("/api/members")).path("data");
        JsonNode categories = json(get("/api/categories")).path("data");
        long memberId = members.get(0).path("id").asLong();
        long foodCategoryId = firstExpenseCategory(categories).path("id").asLong();
        BigDecimal expenseBefore = amountAt(json(get("/api/dashboard?month=2026-09")), "/data/summary/expense");
        String marker = "smoke-http-" + UUID.randomUUID();

        HttpResponse<String> created = write("POST", "/api/transactions", """
                {
                  "kind": "expense",
                  "amount": "88.60",
                  "occurredOn": "2026-09-10",
                  "memberId": %d,
                  "categoryId": %d,
                  "merchant": "验收超市",
                  "location": "杭州",
                  "note": "%s"
                }
                """.formatted(memberId, foodCategoryId, marker));
        assertThat(created.statusCode()).isEqualTo(201);
        long createdId = json(created).at("/data/id").asLong();

        JsonNode filtered = json(get("/api/transactions?month=2026-09&kind=expense&q="
                + URLEncoder.encode(marker, StandardCharsets.UTF_8))).path("data");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("id").asLong()).isEqualTo(createdId);
        assertThat(filtered.get(0).path("amount").asText()).isEqualTo("88.60");

        BigDecimal expenseAfter = amountAt(json(get("/api/dashboard?month=2026-09")), "/data/summary/expense");
        assertThat(expenseAfter).isEqualByComparingTo(expenseBefore.add(new BigDecimal("88.60")));

        JsonNode analysis = json(get("/api/analysis?month=2026-09")).path("data");
        assertThat(analysis.path("insights").isArray()).isTrue();

        HttpResponse<String> export = get("/api/export.csv?month=2026-09&kind=expense&q="
                + URLEncoder.encode(marker, StandardCharsets.UTF_8));
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue(HttpHeaders.CONTENT_TYPE)).contains("text/csv;charset=UTF-8");
        assertThat(export.body()).contains(marker).contains("88.60");

        assertThat(write("DELETE", "/api/transactions/" + createdId, null).statusCode()).isEqualTo(204);
        assertThat(write("POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
        assertThat(get("/api/session").statusCode()).isEqualTo(401);
    }

    private JsonNode firstExpenseCategory(JsonNode categories) {
        for (JsonNode category : categories) {
            if ("expense".equals(category.path("kind").asText())) {
                return category;
            }
        }
        throw new AssertionError("seeded expense category is required for smoke workflow");
    }

    private BigDecimal amountAt(JsonNode body, String pointer) {
        return new BigDecimal(body.at(pointer).asText());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> postForm(String path, Map<String, String> values) throws Exception {
        String form = values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return send(HttpRequest.newBuilder(uri(path))
                .header(csrfHeader, csrfToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    }

    private HttpResponse<String> write(String method, String path, String body) throws Exception {
        loadCsrf();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header(csrfHeader, csrfToken);
        if (body != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        }
        return send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
