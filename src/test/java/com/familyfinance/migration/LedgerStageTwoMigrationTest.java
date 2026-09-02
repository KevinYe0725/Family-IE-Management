package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerStageTwoMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void stageOneUpgradePreservesLedgerFactsAndBackfillsAccountCreatorAndSource() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("stage-one"));
        LedgerFacts before = ledgerFacts(database);

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("4");
        assertThat(ledgerFacts(database)).isEqualTo(before);
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

        assertThat(result.version()).isEqualTo("4");
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
                + "(rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1,date '2026-09-15','PENDING',null,1)");
        assertRejected(result, "insert into recurring_occurrences "
                + "(rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) values "
                + "(1,date '2026-09-15','PENDING',null,1)");
    }

    private static void assertRejected(MigrationResult result, String sql) {
        assertThatThrownBy(() -> result.executeUpdate(sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }

    private static LedgerFacts ledgerFacts(Path database) {
        String sql = "select count(*),sum(amount_cents),min(amount_cents),max(amount_cents),"
                + "count(distinct amount_cents),count(distinct household_id),count(distinct member_id),"
                + "count(distinct category_id),count(distinct kind),count(distinct occurred_on),"
                + "count(merchant),count(location),count(note),min(created_at),max(created_at),"
                + "min(updated_at),max(updated_at) from financial_transactions";
        try (Connection connection = DriverManager.getConnection(MigrationTestSupport.h2Url(database), "sa", "");
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return new LedgerFacts(
                    rows.getLong(1),
                    rows.getLong(2),
                    rows.getLong(3),
                    rows.getLong(4),
                    rows.getLong(5),
                    rows.getLong(6),
                    rows.getLong(7),
                    rows.getLong(8),
                    rows.getLong(9),
                    rows.getLong(10),
                    rows.getLong(11),
                    rows.getLong(12),
                    rows.getLong(13),
                    rows.getObject(14).toString(),
                    rows.getObject(15).toString(),
                    rows.getObject(16).toString(),
                    rows.getObject(17).toString());
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not snapshot migration fixture", exception);
        }
    }

    private record LedgerFacts(
            long count,
            long amountSum,
            long minimumAmount,
            long maximumAmount,
            long distinctAmounts,
            long households,
            long members,
            long categories,
            long kinds,
            long occurredDates,
            long merchants,
            long locations,
            long notes,
            String minimumCreatedAt,
            String maximumCreatedAt,
            String minimumUpdatedAt,
            String maximumUpdatedAt) {
    }
}
