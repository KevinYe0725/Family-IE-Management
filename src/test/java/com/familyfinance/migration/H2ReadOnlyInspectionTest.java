package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2ReadOnlyInspectionTest {

    @TempDir
    Path tempDir;

    @Test
    void metadataInspectionWithReadOnlyAccessLeavesLegacyPrimaryBytesUnchanged() throws Exception {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("family-finance"));
        Path primaryFile = Path.of(database + ".mv.db");
        long sizeBefore = Files.size(primaryFile);
        byte[] hashBefore = sha256(primaryFile);

        try (Connection connection = DriverManager.getConnection(
                        "jdbc:h2:file:" + database + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "");
                ResultSet result = connection.createStatement().executeQuery(
                        "select count(*) from information_schema.tables where table_schema = 'PUBLIC' "
                                + "and table_name = 'flyway_schema_history'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isZero();
        }

        assertThat(Files.size(primaryFile)).isEqualTo(sizeBefore);
        assertThat(sha256(primaryFile)).isEqualTo(hashBefore);
    }

    @Test
    void projectRuntimeUsesThePinnedH2VersionForInspectionCompatibility() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:runtime-version", "sa", "")) {
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("2.3.232");
        }
    }

    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
