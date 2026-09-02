package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayFreshDatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void freshDatabaseRunsV1AndV2() {
        MigrationResult result = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("fresh"));

        assertThat(result.version()).isEqualTo("2");
        assertThat(result.tables()).contains("APP_USERS", "HOUSEHOLD_MEMBERSHIPS", "FAMILY_INVITES");
    }
}
