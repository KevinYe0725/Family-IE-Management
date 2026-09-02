package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerIntegrityMigrationTest {

    @TempDir Path tempDir;

    @Test
    void validStageOneAndV6RowsMigrateThroughV7WithCompositeIntegrityConstraints() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("valid-v7"));
        MigrationResult versionSix = MigrationTestSupport.migrateExistingDatabaseTo(database, "6");
        versionSix.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                + "values (1,'2026-09','CATEGORY',1,10000,1,true)");

        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(result.version()).isEqualTo("8");
        assertThat(result.queryLong("select count(*) from financial_transactions")).isEqualTo(12);
        assertThat(result.queryLong("select count(*) from information_schema.table_constraints "
                + "where table_schema='PUBLIC' and constraint_name in "
                + "('FK_TRANSACTIONS_MEMBER_HOUSEHOLD_V7','FK_TRANSACTIONS_CATEGORY_HOUSEHOLD_KIND_V7',"
                + "'FK_BUDGETS_CATEGORY_HOUSEHOLD_KIND_V7')")).isEqualTo(3);
    }

    @Test
    void v7RejectsCrossHouseholdMemberCategoryAndCategoryKindWrites() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("v7-negative-probes"));
        MigrationResult result = MigrationTestSupport.migrateExistingDatabase(database);
        result.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(2,'EXPENSE','外部支出','#112233',false,current_timestamp,null)");
        long outsiderCategory = result.queryLong("select id from categories where household_id=2");
        result.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(1,'INCOME','家庭收入','#445566',false,current_timestamp,null)");
        long incomeCategory = result.queryLong(
                "select id from categories where household_id=1 and kind='INCOME'");

        assertRejected(result, "update financial_transactions set member_id=2 where id=1");
        assertRejected(result, "update financial_transactions set category_id=" + outsiderCategory + " where id=1");
        assertRejected(result, "update financial_transactions set kind='INCOME' where id=1");
        assertRejected(result, "insert into budgets "
                + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                + "values (1,'2026-10','CATEGORY'," + incomeCategory + ",10000,1,true)");
        result.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,amount_cents,version,active) "
                + "values (1,'2026-10','TOTAL',10000,1,true)");
        long budgetId = result.queryLong(
                "select id from budgets where household_id=1 and period_month='2026-10'");
        assertRejected(result, revisionInsert(budgetId, incomeCategory));
    }

    @Test
    void corruptV6CrossHouseholdMemberFailsClosedAtNamedPrevalidationGuard() {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve("corrupt-member"));
        MigrationResult versionSix = MigrationTestSupport.migrateExistingDatabaseTo(database, "6");
        versionSix.executeUpdate("update financial_transactions set member_id=2 where id=1");

        assertMigrationFailsWithGuard(database, "CK_V7_TRANSACTION_MEMBER_HOUSEHOLD_GUARD");
    }

    @Test
    void corruptV6CategoryHouseholdOrKindFailsClosedAtNamedPrevalidationGuard() {
        Path crossHousehold = StageOneDatabaseFixture.createWithSecondHousehold(
                tempDir.resolve("corrupt-category-household"));
        MigrationResult crossHouseholdV6 = MigrationTestSupport.migrateExistingDatabaseTo(crossHousehold, "6");
        crossHouseholdV6.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(2,'EXPENSE','外部支出','#112233',false,current_timestamp,null)");
        long outsiderCategory = crossHouseholdV6.queryLong("select id from categories where household_id=2");
        crossHouseholdV6.executeUpdate(
                "update financial_transactions set category_id=" + outsiderCategory + " where id=1");
        assertMigrationFailsWithGuard(crossHousehold, "CK_V7_TRANSACTION_CATEGORY_KIND_GUARD");

        Path wrongKind = StageOneDatabaseFixture.create(tempDir.resolve("corrupt-category-kind"));
        MigrationResult wrongKindV6 = MigrationTestSupport.migrateExistingDatabaseTo(wrongKind, "6");
        wrongKindV6.executeUpdate("update financial_transactions set kind='INCOME' where id=1");
        assertMigrationFailsWithGuard(wrongKind, "CK_V7_TRANSACTION_CATEGORY_KIND_GUARD");
    }

    @Test
    void corruptV6BudgetOrRevisionCategoryKindFailsClosedAtNamedPrevalidationGuard() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("corrupt-budget-kind"));
        MigrationResult versionSix = MigrationTestSupport.migrateExistingDatabaseTo(database, "6");
        versionSix.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(1,'INCOME','收入预算错误','#778899',false,current_timestamp,null)");
        long incomeCategory = versionSix.queryLong(
                "select id from categories where household_id=1 and kind='INCOME'");
        versionSix.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                + "values (1,'2026-11','CATEGORY'," + incomeCategory + ",10000,1,true)");

        assertMigrationFailsWithGuard(database, "CK_V7_BUDGET_CATEGORY_KIND_GUARD");
    }

    @Test
    void corruptV6RevisionCategoryKindFailsClosedAtNamedPrevalidationGuard() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("corrupt-revision-kind"));
        MigrationResult versionSix = MigrationTestSupport.migrateExistingDatabaseTo(database, "6");
        versionSix.executeUpdate("insert into categories "
                + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                + "(1,'INCOME','修订收入错误','#778899',false,current_timestamp,null)");
        long incomeCategory = versionSix.queryLong(
                "select id from categories where household_id=1 and kind='INCOME'");
        versionSix.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,amount_cents,version,active) "
                + "values (1,'2026-12','TOTAL',10000,1,true)");
        long budgetId = versionSix.queryLong(
                "select id from budgets where household_id=1 and period_month='2026-12'");
        versionSix.executeUpdate(revisionInsert(budgetId, incomeCategory));

        assertMigrationFailsWithGuard(database, "CK_V7_BUDGET_CATEGORY_KIND_GUARD");
    }

    @Test
    void everyFailedV7GuardCanBeDataFixedFlywayRepairedAndRetried() {
        assertGuardCanBeRepaired(
                "restart-member",
                "V7_TRANSACTION_MEMBER_GUARD",
                v6 -> v6.executeUpdate("update financial_transactions set member_id=2 where id=1"),
                v6 -> v6.executeUpdate("update financial_transactions set member_id=1 where id=1"),
                "CK_V7_TRANSACTION_MEMBER_HOUSEHOLD_GUARD");
        assertGuardCanBeRepaired(
                "restart-category",
                "V7_TRANSACTION_CATEGORY_GUARD",
                v6 -> v6.executeUpdate("update financial_transactions set kind='INCOME' where id=1"),
                v6 -> v6.executeUpdate("update financial_transactions set kind='EXPENSE' where id=1"),
                "CK_V7_TRANSACTION_CATEGORY_KIND_GUARD");
        assertGuardCanBeRepaired(
                "restart-budget",
                "V7_BUDGET_CATEGORY_GUARD",
                v6 -> {
                    v6.executeUpdate("insert into categories "
                            + "(household_id,kind,name,color,is_default,created_at,parent_id) values "
                            + "(1,'INCOME','重试错误预算','#778899',false,current_timestamp,null)");
                    long incomeCategory = v6.queryLong(
                            "select id from categories where household_id=1 and kind='INCOME'");
                    v6.executeUpdate("insert into budgets "
                            + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                            + "values (1,'2027-01','CATEGORY'," + incomeCategory + ",10000,1,true)");
                },
                v6 -> v6.executeUpdate("delete from budgets where period_month='2027-01'"),
                "CK_V7_BUDGET_CATEGORY_KIND_GUARD");
    }

    private void assertGuardCanBeRepaired(
            String databaseName,
            String guardTable,
            Consumer<MigrationResult> corrupt,
            Consumer<MigrationResult> repairData,
            String guardConstraint) {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve(databaseName));
        MigrationResult versionSix = MigrationTestSupport.migrateExistingDatabaseTo(database, "6");
        corrupt.accept(versionSix);

        assertMigrationFailsWithGuard(database, guardConstraint);
        assertThat(versionSix.queryLong("select count(*) from \"flyway_schema_history\" "
                + "where \"version\"='7' and \"success\"=false")).isEqualTo(1);
        assertThat(versionSix.queryLong("select count(*) from information_schema.tables "
                + "where table_schema='PUBLIC' and table_name='" + guardTable + "'")).isEqualTo(1);

        repairData.accept(versionSix);
        Flyway.configure()
                .dataSource(versionSix.databaseUrl(), "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .repair();

        MigrationResult migrated = MigrationTestSupport.migrateExistingDatabase(database);
        assertThat(migrated.version()).isEqualTo("8");
        assertThat(migrated.queryLong("select count(*) from information_schema.tables "
                + "where table_schema='PUBLIC' and table_name like 'V7_%_GUARD'")).isZero();
    }

    private static void assertMigrationFailsWithGuard(Path database, String guardConstraint) {
        ch.qos.logback.classic.Logger flywayLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.flywaydb.core.internal.command.DbMigrate");
        ch.qos.logback.classic.Level previousLevel = flywayLogger.getLevel();
        try {
            flywayLogger.setLevel(ch.qos.logback.classic.Level.OFF);
            assertThatThrownBy(() -> MigrationTestSupport.migrateExistingDatabase(database))
                    .hasStackTraceContaining(guardConstraint);
        } finally {
            flywayLogger.setLevel(previousLevel);
        }
    }

    private static String revisionInsert(long budgetId, long oldCategoryId) {
        return "insert into budget_revisions "
                + "(household_id,budget_id,old_amount_cents,new_amount_cents,changed_by,changed_at,"
                + "old_period_month,new_period_month,old_scope_type,new_scope_type,old_category_id,"
                + "new_category_id,old_member_id,new_member_id,old_active,new_active) values "
                + "(1," + budgetId + ",9000,10000,1,current_timestamp,'2026-10','2026-10',"
                + "'CATEGORY','TOTAL'," + oldCategoryId + ",null,null,null,true,true)";
    }

    private static void assertRejected(MigrationResult result, String sql) {
        assertThatThrownBy(() -> result.executeUpdate(sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }
}
