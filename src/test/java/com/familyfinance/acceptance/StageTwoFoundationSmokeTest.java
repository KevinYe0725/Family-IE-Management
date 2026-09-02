package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.migration.StageOneDatabaseFixture;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageTwoFoundationSmokeTest {

    private static final Path LEGACY_DATABASE = createLegacyDatabase();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> h2Url(LEGACY_DATABASE));
        registry.add("app.seed.enabled", () -> false);
    }

    @Test
    void legacyDemoMigratesToEmailOwnerAndSupportsCreateInviteJoinAndRoleDenial() throws Exception {
        HttpClient legacyOwner = newClient();
        assertThat(postForm(legacyOwner, "/api/auth/login", Map.of("username", "demo", "password", "demo1234"))
                .statusCode()).isEqualTo(200);
        JsonNode legacySession = json(get(legacyOwner, "/api/session")).path("data");
        assertThat(legacySession.path("email").asString()).isEqualTo("demo@local.family");
        assertThat(legacySession.path("role").asString()).isEqualTo("OWNER");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String ownerEmail = "foundation-owner-" + suffix + "@example.com";
        String memberEmail = "foundation-member-" + suffix + "@example.com";
        assertThat(write(newClient(), "POST", "/api/auth/register", """
                {"email":"%s","displayName":"新家庭所有者","password":"family-pass-2026","mode":"CREATE","householdName":"基础验收家庭"}
                """.formatted(ownerEmail)).statusCode()).isEqualTo(201);

        HttpClient newOwner = newClient();
        assertThat(postForm(newOwner, "/api/auth/login", Map.of("username", ownerEmail, "password", "family-pass-2026"))
                .statusCode()).isEqualTo(200);
        String inviteToken = json(write(newOwner, "POST", "/api/family/invites", "{\"role\":\"MEMBER\"}"))
                .path("data").path("token").asString();
        assertThat(inviteToken).isNotBlank();

        assertThat(write(newClient(), "POST", "/api/auth/register", """
                {"email":"%s","displayName":"受邀成员","password":"family-pass-2026","mode":"JOIN","inviteToken":"%s"}
                """.formatted(memberEmail, inviteToken)).statusCode()).isEqualTo(201);

        HttpClient member = newClient();
        assertThat(postForm(member, "/api/auth/login", Map.of("username", memberEmail, "password", "family-pass-2026"))
                .statusCode()).isEqualTo(200);
        long memberMembershipId = membershipIdFor(member, memberEmail);
        assertThat(write(member, "PATCH", "/api/family/memberships/" + memberMembershipId, "{\"role\":\"ADMIN\"}")
                .statusCode()).isEqualTo(403);
    }

    private long membershipIdFor(HttpClient client, String email) throws Exception {
        for (JsonNode membership : json(get(client, "/api/family/memberships")).path("data").path("items")) {
            if (email.equals(membership.path("email").asString())) {
                return membership.path("id").asLong();
            }
        }
        throw new AssertionError("joined member was absent from its household memberships");
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return send(client, HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> postForm(HttpClient client, String path, Map<String, String> values) throws Exception {
        String form = values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        Csrf csrf = csrf(client);
        return send(client, HttpRequest.newBuilder(uri(path))
                .header(csrf.header(), csrf.token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build());
    }

    private HttpResponse<String> write(HttpClient client, String method, String path, String body) throws Exception {
        Csrf csrf = csrf(client);
        return send(client, HttpRequest.newBuilder(uri(path))
                .header(csrf.header(), csrf.token())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private Csrf csrf(HttpClient client) throws Exception {
        JsonNode csrf = json(get(client, "/api/csrf")).path("data");
        return new Csrf(csrf.path("headerName").asString(), csrf.path("token").asString());
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

    private static Path createLegacyDatabase() {
        Path database = Path.of("target", "stage-two-foundation-smoke-" + UUID.randomUUID()).toAbsolutePath();
        StageOneDatabaseFixture.create(database);
        try (Connection connection = DriverManager.getConnection(h2Url(database), "sa", "");
                PreparedStatement statement = connection.prepareStatement(
                        "update app_users set password_hash = ? where username = 'demo'")) {
            statement.setString(1, new BCryptPasswordEncoder().encode("demo1234"));
            statement.executeUpdate();
            return database;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not prepare the legacy demo database", exception);
        }
    }

    private static String h2Url(Path database) {
        return "jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private record Csrf(String header, String token) {
    }
}
