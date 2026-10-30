package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SourceFilterQaLauncherIsolationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void launcherIgnoresInvalidExternalDatasourceAndFlywaySettings() throws Exception {
        verifyLauncher("invalid.datasource.Driver");
    }

    @Test
    void launcherIgnoresInvalidFlywayWhenExternalDatasourceDriverIsValid() throws Exception {
        verifyLauncher("org.h2.Driver");
    }

    private void verifyLauncher(String externalDatasourceDriver) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, SourceFilterQaLauncher.class.getName());
        Path log = temporaryDirectory.resolve("launcher.log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        environment.put("SPRING_DATASOURCE_URL", "jdbc:deliberately-invalid:datasource");
        environment.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", externalDatasourceDriver);
        environment.put("SPRING_DATASOURCE_USERNAME", "invalid-external-user");
        environment.put("SPRING_DATASOURCE_PASSWORD", "invalid-external-password");
        environment.put("SPRING_FLYWAY_URL", "jdbc:deliberately-invalid:flyway");
        environment.put("SPRING_FLYWAY_DRIVER_CLASS_NAME", "invalid.flyway.Driver");
        environment.put("SPRING_FLYWAY_USER", "invalid-external-user");
        environment.put("SPRING_FLYWAY_PASSWORD", "invalid-external-password");
        environment.put("SPRING_FLYWAY_LOCATIONS", "classpath:missing-migrations");
        environment.put("FAMILY_QA_SOURCE_PORT", "0");
        Process process = builder.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
            String output = "";
            while (System.nanoTime() < deadline) {
                output = Files.readString(log);
                if (output.contains("QA_SOURCE_READY ") || !process.isAlive()) break;
                Thread.sleep(100);
            }
            assertThat(output).as("launcher output tail: %s", output.substring(Math.max(0, output.length() - 3000)))
                    .contains("QA_SOURCE_READY ");
            assertThat(output).contains("QA_SOURCE_DATABASE jdbc:h2:mem:qa_source_filter");
            var match = Pattern.compile("QA_SOURCE_READY http://127\\.0\\.0\\.1:(\\d+)/").matcher(output);
            assertThat(match.find()).isTrue();
            int port = Integer.parseInt(match.group(1));
            assertThat(port).isPositive().isNotEqualTo(18801);

            HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            URI base = URI.create("http://127.0.0.1:" + port);
            HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(base.resolve("/api/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(csrf.statusCode()).isEqualTo(200);
            var token = new ObjectMapper().readTree(csrf.body()).path("data");
            HttpRequest login = HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                    .header(token.path("headerName").asText(), token.path("token").asText())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=demo&password=demo1234")).build();
            assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            HttpResponse<String> list = client.send(HttpRequest.newBuilder(base.resolve(
                    "/api/transactions?month=2026-10&kind=expense&sourceType=RECURRING&page=0&size=50")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.headers().firstValue("X-Total-Elements")).contains("51");
            assertThat(list.body()).contains("QA-RECURRING").doesNotContain("QA-FOREIGN-DO-NOT-SHOW");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
