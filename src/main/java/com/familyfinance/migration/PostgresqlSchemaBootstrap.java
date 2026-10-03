package com.familyfinance.migration;

import org.flywaydb.core.Flyway;

/** Initialize a new PostgreSQL schema before importing legacy data, without starting jobs or the web app. */
public final class PostgresqlSchemaBootstrap {
    private PostgresqlSchemaBootstrap() {}

    public static void main(String[] args) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("PostgreSQL datasource required");
        }
        Flyway.configure().dataSource(url, System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .locations("classpath:db/migration-postgresql").baselineOnMigrate(false).load().migrate();
    }
}
