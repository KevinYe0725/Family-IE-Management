package com.familyfinance.migration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

/** Test-gate helper; all callers point it at isolated synthetic databases. */
public final class MigrationStateFixtureCli {

    private MigrationStateFixtureCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected database base path and action");
        }
        String url = MigrationTestSupport.h2Url(Path.of(args[0]));
        switch (args[1]) {
            case "migrate-to-6" -> flyway(url, "6").migrate();
            case "migrate-to-7" -> flyway(url, "7").migrate();
            case "fail-v7-budget" -> createExpectedFailedV7(url);
            case "repair-v7-budget" -> repairExpectedFailedV7(url);
            case "assert-version-8" -> assertVersionEight(url);
            case "make-future-history" -> updateHistoryVersion(url, "7", "99");
            case "make-ambiguous-history" -> updateHistoryVersion(url, "7", "not-numeric");
            case "assert-future-history" -> assertHistoryVersion(url, "99");
            case "assert-ambiguous-history" -> assertHistoryVersion(url, "not-numeric");
            case "print-history-snapshot" -> printHistorySnapshot(url);
            default -> throw new IllegalArgumentException("Unknown migration fixture action: " + args[1]);
        }
    }

    private static void createExpectedFailedV7(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.executeUpdate("insert into categories "
                    + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                    + "(1,'INCOME','启动失败预算','#778899',false,current_timestamp,null)");
            statement.executeUpdate("insert into budgets "
                    + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                    + "select 1,'2027-02','CATEGORY',id,10000,1,true from categories "
                    + "where household_id=1 and name='启动失败预算'");
        }
        try {
            flyway(url, null).migrate();
            throw new AssertionError("Expected V7 guard failure");
        } catch (FlywayException expected) {
            if (!stackMessages(expected).contains("CK_V7_BUDGET_CATEGORY_KIND_GUARD")) {
                throw expected;
            }
        }
    }

    private static void repairExpectedFailedV7(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.executeUpdate("delete from budgets where period_month='2027-02'");
        }
        flyway(url, null).repair();
    }

    private static void assertVersionEight(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var rows = connection.createStatement().executeQuery(
                        "select count(*) from \"flyway_schema_history\" "
                                + "where \"version\"='8' and \"success\"=true")) {
            rows.next();
            if (rows.getLong(1) != 1) throw new AssertionError("Expected one successful V8 row");
        }
    }

    private static void updateHistoryVersion(String url, String from, String to) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.prepareStatement(
                        "update \"flyway_schema_history\" set \"version\"=? where \"version\"=?")) {
            statement.setString(1, to);
            statement.setString(2, from);
            if (statement.executeUpdate() != 1) {
                throw new AssertionError("Expected exactly one migration history row to change");
            }
        }
    }

    private static void assertHistoryVersion(String url, String expected) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.prepareStatement(
                        "select count(*) from \"flyway_schema_history\" where \"version\"=?")) {
            statement.setString(1, expected);
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 1) {
                    throw new AssertionError("Expected migration history version " + expected);
                }
            }
        }
    }

    private static void printHistorySnapshot(String url) throws Exception {
        String sql = "select \"installed_rank\",\"version\",\"description\",\"type\",\"script\","
                + "\"checksum\",\"installed_by\",\"installed_on\",\"execution_time\",\"success\" "
                + "from \"flyway_schema_history\" order by \"installed_rank\"";
        try (var connection = DriverManager.getConnection(
                        url + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
                var rows = connection.createStatement().executeQuery(sql)) {
            StringBuilder snapshot = new StringBuilder();
            while (rows.next()) {
                for (int column = 1; column <= 10; column++) {
                    if (column > 1) snapshot.append('\u001f');
                    snapshot.append(String.valueOf(rows.getObject(column)));
                }
                snapshot.append('\n');
            }
            System.out.println(Base64.getEncoder().encodeToString(
                    snapshot.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static String stackMessages(Throwable exception) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(' ').append(current.getMessage());
        }
        return messages.toString();
    }
}
