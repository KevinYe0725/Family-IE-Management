package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayStageOneUpgradeTest {

    @TempDir
    Path tempDir;

    @Test
    void stageOneDatabaseIsBaselinedAndKeepsLedgerRows() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("stage1"));

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("2");
        assertThat(result.queryLong("select count(*) from financial_transactions")).isEqualTo(12);
        assertThat(result.queryString("select email from app_users where username='demo'"))
                .isEqualTo("demo@local.family");
    }
}
