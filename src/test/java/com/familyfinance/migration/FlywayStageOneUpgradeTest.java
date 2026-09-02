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
        assertThat(result.queryString("select role from household_memberships where household_id=1 and user_id=1"))
                .isEqualTo("OWNER");
        assertThat(result.queryLong("select linked_user_id from family_members where id=1")).isEqualTo(1);
        assertThat(result.queryLong("select count(*) from information_schema.table_constraints "
                + "where table_schema='PUBLIC' and table_name='HOUSEHOLD_MEMBERSHIPS' and constraint_type='UNIQUE'"))
                .isEqualTo(1);
        assertThat(result.queryLong("select count(*) from information_schema.table_constraints "
                + "where table_schema='PUBLIC' and table_name='HOUSEHOLD_MEMBERSHIPS' and constraint_type='FOREIGN KEY'"))
                .isEqualTo(2);
        assertThat(result.queryLong("select count(*) from information_schema.table_constraints "
                + "where table_schema='PUBLIC' and table_name='FAMILY_INVITES' and constraint_type='UNIQUE'"))
                .isEqualTo(1);
        assertThat(result.queryLong("select count(*) from information_schema.table_constraints "
                + "where table_schema='PUBLIC' and table_name='FAMILY_INVITES' and constraint_type='FOREIGN KEY'"))
                .isEqualTo(2);
    }

    @Test
    void stageOneDatabaseWithMultipleUsersGetsUniqueCompatibilityEmails() {
        Path database = StageOneDatabaseFixture.createWithAdditionalUser(tempDir.resolve("multiple-users"));

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("2");
        assertThat(result.queryString("select email from app_users where username='demo'"))
                .isEqualTo("demo@local.family");
        assertThat(result.queryString("select email from app_users where username='legacy'"))
                .isEqualTo("legacy-2@local.family");
    }
}
