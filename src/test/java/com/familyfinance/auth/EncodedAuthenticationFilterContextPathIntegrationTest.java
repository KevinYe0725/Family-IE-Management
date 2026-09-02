package com.familyfinance.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=true", "server.servlet.context-path=/family"})
class EncodedAuthenticationFilterContextPathIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void restoreDemoUser() {
        jdbc.update("update app_users set status='ACTIVE' where email='demo@local.family'");
    }

    @Test
    void contextPathEncodedLoginSegmentsShareCanonicalQuota() throws Exception {
        assertEncodedLoginSharesCanonicalQuota(
                "context-final-login@example.com", "/family/api/auth/%6cogin");
        assertEncodedLoginSharesCanonicalQuota(
                "context-api-login@example.com", "/family/a%70i/auth/login");
    }

    @Test
    void contextPathSuspendedSessionIsInvalidatedOnEncodedChangePassword() throws Exception {
        assertSuspendedSessionInvalidated("/family/api/auth/change-%70assword");
    }

    @Test
    void contextPathSuspendedSessionIsInvalidatedOnEncodedApiSegment() throws Exception {
        assertSuspendedSessionInvalidated("/family/a%70i/auth/change-password");
    }

    private void assertSuspendedSessionInvalidated(String encodedPath) throws Exception {
        HttpClient client = newClient();
        Csrf csrf = csrf(client);
        assertThat(login(client, "/family/api/auth/login", "demo", "demo1234", csrf).statusCode())
                .isEqualTo(200);
        csrf = csrf(client);
        jdbc.update("update app_users set status='SUSPENDED' where email='demo@local.family'");

        HttpResponse<String> denied = send(client, HttpRequest.newBuilder(uri(encodedPath))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(csrf.headerName(), csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(json(denied).path("error").path("code").asText()).isEqualTo("AUTH_REQUIRED");

        HttpResponse<String> clearedSession = send(client, HttpRequest.newBuilder(
                        uri("/family/api/session"))
                .GET().build());
        assertThat(clearedSession.statusCode()).isEqualTo(401);
        assertThat(clearedSession.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(json(clearedSession).path("error").path("code").asText()).isEqualTo("AUTH_REQUIRED");
    }

    private void assertEncodedLoginSharesCanonicalQuota(String username, String encodedPath) throws Exception {
        HttpClient client = newClient();
        Csrf csrf = csrf(client);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(login(client, "/family/api/auth/login", username, "wrong-password", csrf).statusCode())
                    .isEqualTo(401);
        }
        assertRateLimited(login(client, "/family/api/auth/login", username, "wrong-password", csrf));
        assertRateLimited(login(client, encodedPath, username, "wrong-password", csrf));
    }

    private void assertRateLimited(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
    }

    private HttpResponse<String> login(
            HttpClient client, String path, String username, String password, Csrf csrf) throws Exception {
        String form = form(Map.of("username", username, "password", password));
        return send(client, HttpRequest.newBuilder(uri(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(csrf.headerName(), csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    }

    private Csrf csrf(HttpClient client) throws Exception {
        JsonNode data = json(send(client, HttpRequest.newBuilder(uri("/family/api/csrf"))
                .GET().build())).path("data");
        return new Csrf(data.path("headerName").asText(), data.path("token").asText());
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
    }

    private record Csrf(String headerName, String token) {
    }
}
