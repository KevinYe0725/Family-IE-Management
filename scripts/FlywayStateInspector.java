import java.sql.DriverManager;
import java.util.Set;

public final class FlywayStateInspector {
    private static final Set<String> ALLOWED_STATES = Set.of(
            "NO_HISTORY", "BEHIND_CURRENT", "CURRENT", "FAILED", "FUTURE", "AMBIGUOUS");

    private FlywayStateInspector() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].startsWith("jdbc:h2:file:") || !args[1].matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Expected a local H2 JDBC URL and positive latest migration version");
        }
        String jdbcUrl = args[0];
        long latestMigrationVersion = Long.parseLong(args[1]);
        Class.forName("org.h2.Driver");
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            connection.setReadOnly(true);
            if (!hasFlywayHistory(connection)) {
                System.out.println("NO_HISTORY");
                return;
            }
            String state = readMigrationState(connection, latestMigrationVersion);
            if (!ALLOWED_STATES.contains(state)) {
                throw new IllegalStateException("Unsupported migration state returned by H2");
            }
            System.out.println(state);
        }
    }

    private static boolean hasFlywayHistory(java.sql.Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "select count(*) from information_schema.tables "
                        + "where table_schema='PUBLIC' and table_name='flyway_schema_history'");
                var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("History presence query returned no row");
            return rows.getLong(1) == 1;
        }
    }

    private static String readMigrationState(java.sql.Connection connection, long latestVersion) throws Exception {
        String sql = "select case "
                + "when exists (select 1 from \"flyway_schema_history\" where \"success\" = false) "
                + "then 'FAILED' "
                + "when exists (select 1 from \"flyway_schema_history\" where \"version\" is not null "
                + "and not regexp_like(\"version\", '^[0-9]+$')) then 'AMBIGUOUS' "
                + "when not exists (select 1 from \"flyway_schema_history\" where \"success\" = true "
                + "and \"version\" is not null and regexp_like(\"version\", '^[0-9]+$')) "
                + "then 'AMBIGUOUS' "
                + "when exists (select 1 from \"flyway_schema_history\" where \"success\" = true "
                + "and \"version\" is not null group by \"version\" having count(*) > 1) "
                + "then 'AMBIGUOUS' "
                + "when (select max(cast(\"version\" as bigint)) from \"flyway_schema_history\" "
                + "where \"success\" = true and regexp_like(\"version\", '^[0-9]+$')) > ? then 'FUTURE' "
                + "when (select max(cast(\"version\" as bigint)) from \"flyway_schema_history\" "
                + "where \"success\" = true and regexp_like(\"version\", '^[0-9]+$')) = ? then 'CURRENT' "
                + "when (select max(cast(\"version\" as bigint)) from \"flyway_schema_history\" "
                + "where \"success\" = true and regexp_like(\"version\", '^[0-9]+$')) < ? "
                + "then 'BEHIND_CURRENT' else 'AMBIGUOUS' end as MIGRATION_STATE";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, latestVersion);
            statement.setLong(2, latestVersion);
            statement.setLong(3, latestVersion);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Migration state query returned no row");
                String state = rows.getString(1);
                if (rows.next()) throw new IllegalStateException("Migration state query returned multiple rows");
                return state;
            }
        }
    }
}
