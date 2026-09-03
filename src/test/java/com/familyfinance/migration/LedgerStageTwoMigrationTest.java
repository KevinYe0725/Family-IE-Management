package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerStageTwoMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void stageOneUpgradePreservesLedgerFactsAndBackfillsAccountCreatorAndSource() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("stage-one"));
        List<LegacyTransactionRow> before = legacyTransactionRows(database);

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("11");
        assertThat(legacyTransactionRows(database)).containsExactlyElementsOf(before);
        assertThat(result.queryLong("select count(*) from financial_transactions")).isEqualTo(12);
        assertThat(result.queryLong("select count(*) from financial_accounts where household_id=1 "
                + "and name='默认账户' and type='CASH' and currency='CNY' and opening_balance_cents=0 "
                + "and archived_at is null")).isEqualTo(1);
        assertThat(result.queryLong("select count(*) from financial_transactions where account_id is null"))
                .isZero();
        assertThat(result.queryLong(
                "select count(*) from financial_transactions where created_by_user_id is null"))
                .isZero();
        assertThat(result.queryLong("select count(*) from financial_transactions where created_by_user_id=1"))
                .isEqualTo(12);
        assertThat(result.queryLong("select count(*) from financial_transactions "
                + "where source_type='MANUAL' and source_id is null"))
                .isEqualTo(12);
        assertThat(result.queryLong("select count(*) from categories where parent_id is not null")).isZero();
    }

    @Test
    void archivedV3HouseholdUsesItsInactiveLinkedUserAndPreservesEveryLedgerColumn() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("archived-household"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        List<LegacyTransactionRow> before = legacyTransactionRows(database);
        versionThree.executeUpdate("update households set status='ARCHIVED',archived_at=current_timestamp where id=1");
        versionThree.executeUpdate("update app_users set status='SUSPENDED' where id=1");
        versionThree.executeUpdate("update household_memberships set status='SUSPENDED' where user_id=1");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("11");
        assertThat(legacyTransactionRows(database)).containsExactlyElementsOf(before);
        assertThat(result.queryLong("select count(*) from financial_transactions where created_by_user_id=1"))
                .isEqualTo(12);
    }

    @Test
    void v3HouseholdWithoutActiveOwnerUsesItsSuspendedOwnerAndPreservesEveryLedgerColumn() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("suspended-owner"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        List<LegacyTransactionRow> before = legacyTransactionRows(database);
        versionThree.executeUpdate("update family_members set linked_user_id=null where household_id=1");
        versionThree.executeUpdate("update app_users set status='SUSPENDED' where id=1");
        versionThree.executeUpdate("update household_memberships set status='SUSPENDED' where user_id=1");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("11");
        assertThat(legacyTransactionRows(database)).containsExactlyElementsOf(before);
        assertThat(result.queryLong("select count(*) from financial_transactions where created_by_user_id=1"))
                .isEqualTo(12);
    }

    @Test
    void v3CreatorFallbackPrefersAnyMembershipBeforeTheSmallestHouseholdUser() {
        Path database = StageOneDatabaseFixture.createWithAdditionalUser(tempDir.resolve("membership-fallback"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        versionThree.executeUpdate("update family_members set linked_user_id=null where household_id=1");
        versionThree.executeUpdate("delete from household_memberships where household_id=1");
        versionThree.executeUpdate("insert into household_memberships "
                + "(household_id,user_id,role,status,joined_at) values "
                + "(1,2,'MEMBER','SUSPENDED',timestamp with time zone '2026-09-01 00:00:00+00')");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.queryLong("select count(*) from financial_transactions where created_by_user_id=2"))
                .isEqualTo(12);
    }

    @Test
    void v3CreatorFallbackUsesSmallestHouseholdUserWhenThereAreNoMemberships() {
        Path database = StageOneDatabaseFixture.createWithAdditionalUser(tempDir.resolve("user-fallback"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        versionThree.executeUpdate("update family_members set linked_user_id=null where household_id=1");
        versionThree.executeUpdate("delete from household_memberships where household_id=1");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.queryLong("select count(*) from financial_transactions where created_by_user_id=1"))
                .isEqualTo(12);
    }

    @Test
    void v3HouseholdWithTransactionsButNoSameHouseholdUserFailsClosed() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("userless-corrupt-household"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        versionThree.executeUpdate("update family_members set linked_user_id=null where household_id=1");
        versionThree.executeUpdate("delete from household_memberships where household_id=1");
        versionThree.executeUpdate("delete from app_users where household_id=1");

        assertThatThrownBy(() -> MigrationTestSupport.migrateExistingDatabase(database))
                .hasMessageContaining("created_by_user_id");
    }

    @Test
    void currentV3UpgradeCreatesOneDefaultAccountPerHouseholdAndChoosesCreatorsDeterministically() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("version-three"));
        MigrationResult versionThree = MigrationTestSupport.migrateExistingDatabaseTo(database, "3");
        versionThree.executeUpdate("insert into household_memberships "
                + "(household_id,user_id,role,status,joined_at) values "
                + "(2,2,'OWNER','ACTIVE',timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into app_users "
                + "(id,household_id,username,email,display_name,password_hash,status,created_at) values "
                + "(3,2,'member-two','member-two@local.family','成员二','encoded-password','ACTIVE',"
                + "timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into household_memberships "
                + "(household_id,user_id,role,status,joined_at) values "
                + "(2,3,'MEMBER','ACTIVE',timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into family_members "
                + "(id,household_id,linked_user_id,name,role_label,created_at) values "
                + "(4,2,3,'已登录成员','成员',timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into categories "
                + "(id,household_id,kind,name,color,is_default,created_at) values "
                + "(2,2,'EXPENSE','餐饮','#D8664B',true,timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into financial_transactions "
                + "(household_id,member_id,category_id,kind,amount_cents,occurred_on,created_at,updated_at) "
                + "values (2,2,2,'EXPENSE',900,date '2026-09-02',"
                + "timestamp with time zone '2026-09-01 00:00:00+00',"
                + "timestamp with time zone '2026-09-01 00:00:00+00')");
        versionThree.executeUpdate("insert into financial_transactions "
                + "(household_id,member_id,category_id,kind,amount_cents,occurred_on,created_at,updated_at) "
                + "values (2,4,2,'EXPENSE',901,date '2026-09-03',"
                + "timestamp with time zone '2026-09-01 00:00:00+00',"
                + "timestamp with time zone '2026-09-01 00:00:00+00')");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("11");
        assertThat(result.queryLong("select count(*) from financial_accounts")).isEqualTo(2);
        assertThat(result.queryLong("select count(*) from households h where not exists "
                + "(select 1 from financial_accounts a where a.household_id=h.id and a.name='默认账户')"))
                .isZero();
        assertThat(result.queryLong(
                "select created_by_user_id from financial_transactions where amount_cents=900"))
                .isEqualTo(2);
        assertThat(result.queryLong(
                "select created_by_user_id from financial_transactions where amount_cents=901"))
                .isEqualTo(3);
        assertThat(result.queryLong("select count(*) from financial_transactions t join financial_accounts a "
                + "on a.id=t.account_id where a.household_id<>t.household_id"))
                .isZero();
    }

    @Test
    void migratedSchemaRejectsInvalidLedgerBudgetAndRecurringRelationships() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("constraints"));
        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertRejected(result, "insert into financial_accounts "
                + "(household_id,name,type,currency,opening_balance_cents) "
                + "values (1,'默认账户','CASH','CNY',0)");
        assertRejected(result, "update financial_transactions set account_id=null where id=1");
        assertRejected(result, "update financial_transactions set created_by_user_id=null where id=1");
        assertRejected(result, "update financial_transactions set account_id="
                + "(select id from financial_accounts where household_id=2) where id=1");
        assertRejected(result, "update financial_transactions set created_by_user_id=2 where id=1");

        result.executeUpdate("update financial_transactions set source_type='RECURRING',source_id=77 where id=1");
        assertRejected(result, "update financial_transactions set source_type='RECURRING',source_id=77 where id=2");
        assertRejected(result, "update financial_transactions set source_type='RECURRING',source_id=null where id=2");
        assertRejected(result, "update financial_transactions set source_type='MANUAL',source_id=88 where id=2");

        result.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(1,'EXPENSE','支出子类','#112233',false,current_timestamp,1)");
        assertRejected(result, "update categories set parent_id=id where id=1");
        assertRejected(result, "insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(1,'INCOME','错误子类','#112233',false,current_timestamp,1)");

        result.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,10000,1,true)");
        assertRejected(result, "insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,20000,1,true)");
        assertRejected(result, "insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','CATEGORY',null,null,10000,1,true)");
        assertRejected(result, "insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-10','TOTAL',null,null,100000000000,1,true)");

        long accountId = result.queryLong("select id from financial_accounts where household_id=1");
        result.executeUpdate("insert into recurring_rules "
                + "(household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                + "account_id,member_id,category_id,active,created_by) values "
                + "(1,'EXPENSE',500,'MONTHLY',1,15,date '2026-09-15'," + accountId
                + ",1,1,true,1)");
        assertRejected(result, "insert into recurring_rules "
                + "(household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                + "account_id,member_id,category_id,active,created_by) values "
                + "(1,'INCOME',500,'MONTHLY',1,15,date '2026-09-15'," + accountId
                + ",1,1,true,1)");
        result.executeUpdate("insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1,1,date '2026-09-15','PENDING',null,1)");
        assertRejected(result, "insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1,1,date '2026-09-15','PENDING',null,1)");
    }

    @Test
    void budgetRevisionsRequireTheSameHouseholdForBudgetAndActor() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("budget-revision-scope"));
        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);
        result.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-10','TOTAL',null,null,10000,1,true)");
        long budgetId = result.queryLong("select id from budgets where household_id=1");

        result.executeUpdate(revisionInsert(1, budgetId, 1));
        assertRejected(result, revisionInsert(1, budgetId, 2));
        assertRejected(result, revisionInsert(2, budgetId, 2));
    }

    @Test
    void recurringOccurrencesEnforceHouseholdScopeAndAllowUnassignedPendingRows() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("occurrence-scope"));
        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);
        result.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(2,'EXPENSE','外部支出','#112233',false,current_timestamp,null)");
        long householdOneAccount = result.queryLong("select id from financial_accounts where household_id=1");
        long householdTwoAccount = result.queryLong("select id from financial_accounts where household_id=2");
        long householdTwoCategory = result.queryLong("select id from categories where household_id=2");
        result.executeUpdate("insert into financial_transactions "
                + "(household_id,account_id,created_by_user_id,member_id,category_id,kind,amount_cents,"
                + "occurred_on,created_at,updated_at,source_type,source_id) values "
                + "(2," + householdTwoAccount + ",2,2," + householdTwoCategory
                + ",'EXPENSE',700,date '2026-09-04',current_timestamp,current_timestamp,'MANUAL',null)");
        long householdTwoTransaction = result.queryLong(
                "select id from financial_transactions where household_id=2");
        result.executeUpdate("insert into recurring_rules "
                + "(household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                + "account_id,member_id,category_id,active,created_by) values "
                + "(1,'EXPENSE',500,'MONTHLY',1,15,date '2026-09-15'," + householdOneAccount
                + ",1,1,true,1)");
        long ruleId = result.queryLong("select id from recurring_rules where household_id=1");

        result.executeUpdate("insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1," + ruleId + ",date '2026-10-15','PENDING',null,null)");
        result.executeUpdate("insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1," + ruleId + ",date '2026-11-15','PENDING',null,1)");
        result.executeUpdate("insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1," + ruleId + ",date '2026-12-15','CONFIRMED',1,1)");
        assertRejected(result, "insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1," + ruleId + ",date '2027-01-15','PENDING',null,2)");
        assertRejected(result, "insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(2," + ruleId + ",date '2027-02-15','PENDING',null,2)");
        assertRejected(result, "insert into recurring_occurrences "
                + "(household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1," + ruleId + ",date '2027-03-15','CONFIRMED'," + householdTwoTransaction + ",1)");
    }

    private static void assertRejected(MigrationResult result, String sql) {
        assertThatThrownBy(() -> result.executeUpdate(sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }

    private static String revisionInsert(long householdId, long budgetId, long actorId) {
        return "insert into budget_revisions "
                + "(household_id,budget_id,old_period_month,new_period_month,old_scope_type,new_scope_type,"
                + "old_category_id,new_category_id,old_member_id,new_member_id,old_amount_cents,new_amount_cents,"
                + "old_active,new_active,changed_by,changed_at) values ("
                + householdId + "," + budgetId
                + ",'2026-10','2026-10','TOTAL','TOTAL',null,null,null,null,10000,12000,true,true,"
                + actorId + ",current_timestamp)";
    }

    private static List<LegacyTransactionRow> legacyTransactionRows(Path database) {
        String sql = "select id,household_id,member_id,category_id,kind,amount_cents,occurred_on,"
                + "merchant,location,note,created_at,updated_at from financial_transactions order by id";
        try (Connection connection = DriverManager.getConnection(MigrationTestSupport.h2Url(database), "sa", "");
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            List<LegacyTransactionRow> snapshot = new ArrayList<>();
            while (rows.next()) {
                snapshot.add(new LegacyTransactionRow(
                        rows.getLong("id"),
                        rows.getLong("household_id"),
                        rows.getLong("member_id"),
                        rows.getLong("category_id"),
                        rows.getString("kind"),
                        rows.getLong("amount_cents"),
                        rows.getString("occurred_on"),
                        rows.getString("merchant"),
                        rows.getString("location"),
                        rows.getString("note"),
                        rows.getObject("created_at").toString(),
                        rows.getObject("updated_at").toString()));
            }
            return List.copyOf(snapshot);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not snapshot migration fixture", exception);
        }
    }

    private record LegacyTransactionRow(
            long id,
            long householdId,
            long memberId,
            long categoryId,
            String kind,
            long amountCents,
            String occurredOn,
            String merchant,
            String location,
            String note,
            String createdAt,
            String updatedAt) {
    }
}
