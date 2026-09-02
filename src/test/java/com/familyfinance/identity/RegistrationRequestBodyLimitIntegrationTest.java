package com.familyfinance.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.seed.enabled=false")
class RegistrationRequestBodyLimitIntegrationTest {

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
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        JsonNode csrf = json(send(HttpRequest.newBuilder(uri("/api/csrf")).GET().build())).path("data");
        csrfHeader = csrf.path("headerName").asString();
        csrfToken = csrf.path("token").asString();
    }

    @Test
    void chunkedOversizedRegistrationBodyIsRejectedBeforeMvcDeserialization() throws Exception {
        HttpRequest request = chunkedOversizedRequest("/api/auth/register");
        assertThat(request.bodyPublisher().orElseThrow().contentLength()).isEqualTo(-1);
        assertRequestBodyValidation(send(request));
    }

    @Test
    void encodedRegistrationRouteStillAppliesChunkedBodyLimitBeforeMvcDeserialization() throws Exception {
        assertRequestBodyValidation(send(chunkedOversizedRequest("/api/auth/%72egister")));
    }

    @Test
    void encodedApiSegmentStillAppliesChunkedBodyLimitAndCorrelation() throws Exception {
        assertRequestBodyValidation(send(chunkedOversizedRequest("/a%70i/auth/register")));
    }

    @Test
    void correlationAppliesToCanonicalApiRoutesButNotNonApiRoutes() throws Exception {
        HttpResponse<String> api = send(HttpRequest.newBuilder(uri("/api/csrf")).GET().build());
        HttpResponse<String> nonApi = send(HttpRequest.newBuilder(uri("/")).GET().build());

        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(nonApi.statusCode()).isEqualTo(200);
        assertThat(nonApi.headers().firstValue("X-Request-ID")).isEmpty();
    }

    private HttpRequest chunkedOversizedRequest(String path) {
        byte[] body = """
                {"email":"%s","displayName":"大请求","password":"family-pass-2026","mode":"CREATE","householdName":"大请求家庭"}
                """.formatted("a".repeat(5_000)).getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(uri(path))
                .version(HttpClient.Version.HTTP_1_1)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(csrfHeader, csrfToken)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
    }

    private void assertRequestBodyValidation(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        JsonNode error = json(response).path("error");
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fields").path("request").asText()).isNotBlank();
    }

    @Test
    void registrationBodyLimitFilterPreservesCsrfRejection() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/register"))
                .version(HttpClient.Version.HTTP_1_1)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("""
                        {"email":"csrf-filter@example.com","displayName":"CSRF","password":"family-pass-2026","mode":"CREATE","householdName":"CSRF 家庭"}
                        """.getBytes(StandardCharsets.UTF_8))))
                .build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("FORBIDDEN");
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
