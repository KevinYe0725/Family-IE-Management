package com.familyfinance.migration;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;

final class MigrationTestSupport {

    private MigrationTestSupport() {
    }

    static MigrationResult migrateFreshDatabase(Path database) {
        return migrate(database, false);
    }

    static MigrationResult migrateExistingDatabase(Path database) {
        return migrate(database, true, null);
    }

    static MigrationResult migrateExistingDatabaseTo(Path database, String targetVersion) {
        return migrate(database, true, targetVersion);
    }

    private static MigrationResult migrate(Path database, boolean baselineOnMigrate) {
        return migrate(database, baselineOnMigrate, null);
    }

    private static MigrationResult migrate(Path database, boolean baselineOnMigrate, String targetVersion) {
        String url = h2Url(database);
        var configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("1");
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        Flyway flyway = configuration.load();
        flyway.migrate();
        return inspect(url, flyway.info().current().getVersion().getVersion());
    }

    static String h2Url(Path database) {
        return "jdbc:h2:file:" + database.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private static MigrationResult inspect(String url, String version) {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Set<String> tables = new LinkedHashSet<>();
            try (ResultSet resultSet = connection.createStatement().executeQuery(
                    "select table_name from information_schema.tables where table_schema = 'PUBLIC'")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }
            return new MigrationResult(version, tables, url);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect migrated H2 database", exception);
        }
    }

}

record MigrationResult(String version, Set<String> tables, String databaseUrl) {

    void executeUpdate(String sql) {
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            connection.createStatement().executeUpdate(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not execute migration test statement: " + sql, exception);
        }
    }

    long queryLong(String sql) {
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Expected query to return one row: " + sql);
            }
            return resultSet.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not run query: " + sql, exception);
        }
    }

    String queryString(String sql) {
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Expected query to return one row: " + sql);
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not run query: " + sql, exception);
        }
    }
}
