package com.familyfinance.migration;

import java.sql.DriverManager;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

/** Each test context gets a private schema on an explicitly supplied disposable PostgreSQL database. */
public class PostgresqlTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        String url = System.getenv("FAMILY_PG_TEST_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")) throw new IllegalStateException("Disposable PostgreSQL URL required");
        String user = System.getenv().getOrDefault("FAMILY_PG_TEST_USER", System.getProperty("user.name"));
        String password = System.getenv().getOrDefault("FAMILY_PG_TEST_PASSWORD", "");
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        } catch (Exception e) { throw new IllegalStateException("Could not create isolated PostgreSQL test schema", e); }
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "spring.datasource.url=" + url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                "spring.datasource.username=" + user, "spring.datasource.password=" + password,
                "spring.flyway.locations=classpath:db/migration-postgresql", "spring.flyway.baseline-on-migrate=false");
    }
}
