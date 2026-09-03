package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(result.version()).isEqualTo("10");
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

        assertThat(result.version()).isEqualTo("10");
        assertThat(result.queryString("select email from app_users where username='demo'"))
                .isEqualTo("demo@local.family");
        assertThat(result.queryString("select email from app_users where username='legacy'"))
                .isEqualTo("legacy-2@local.family");
    }

    @Test
    void renamedOriginalProfileIsStillTheSingleDemoLink() {
        Path database = StageOneDatabaseFixture.createWithRenamedOriginalMember(tempDir.resolve("renamed-member"));

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertDemoOwnerAndStableLink(result, 1L, 12L);
        assertThat(result.queryString("select name from family_members where id=1")).isEqualTo("重命名成员");
    }

    @Test
    void duplicateKevinProfilesAreDeduplicatedToTheLowestMemberId() {
        Path database = StageOneDatabaseFixture.createWithDuplicateKevinMember(tempDir.resolve("duplicate-kevin"));

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertDemoOwnerAndStableLink(result, 1L, 12L);
        assertThat(result.queryLong("select count(*) from family_members where id=2 and linked_user_id is null"))
                .isEqualTo(1);
        assertThatThrownBy(() -> result.executeUpdate("update family_members set linked_user_id=1 where id=2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }

    @Test
    void legacyDemoHouseholdWithoutMembersGetsOneDeterministicLinkedProfile() {
        Path database = StageOneDatabaseFixture.createWithoutMembers(tempDir.resolve("no-members"));

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertDemoOwnerAndStableLink(result, 1L, 0L);
        assertThat(result.queryString("select name from family_members where id=1")).isEqualTo("演示用户");
        assertThat(result.queryString("select role_label from family_members where id=1")).isEqualTo("所有者");
    }

    @Test
    void migratedSchemaRejectsCrossHouseholdProfileLinks() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("cross-household"));
        MigrationResult versionTwo = MigrationTestSupport.migrateExistingDatabaseTo(database, "2");
        versionTwo.executeUpdate("update family_members set linked_user_id=2 where id=3");
        assertThat(versionTwo.queryLong("select count(*) from family_members "
                + "where household_id=1 and linked_user_id=2")).isEqualTo(1);

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertDemoOwnerAndStableLink(result, 1L, 12L);
        assertThat(result.queryLong("select count(*) from family_members "
                + "where linked_user_id is not null and household_id <> "
                + "(select household_id from app_users where app_users.id=family_members.linked_user_id)"))
                .isZero();
        assertThatThrownBy(() -> result.executeUpdate("update family_members set linked_user_id=1 where id=2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }

    private static void assertDemoOwnerAndStableLink(
            MigrationResult result, long expectedMemberId, long expectedTransactionCount) {
        assertThat(result.version()).isEqualTo("10");
        assertThat(result.queryLong("select count(*) from household_memberships "
                + "where household_id=1 and user_id=1 and role='OWNER' and status='ACTIVE'"))
                .isEqualTo(1);
        assertThat(result.queryLong("select count(*) from family_members where linked_user_id=1"))
                .isEqualTo(1);
        assertThat(result.queryLong("select id from family_members where linked_user_id=1"))
                .isEqualTo(expectedMemberId);
        assertThat(result.queryLong("select count(*) from financial_transactions"))
                .isEqualTo(expectedTransactionCount);
    }
}
