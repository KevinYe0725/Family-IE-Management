package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetRevisionMigrationTest {

    @TempDir Path tempDir;

    @Test
    void v5BackfillsExistingRevisionsAndAllowsManyInactiveButOnlyOneActiveScope() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("budget-v4-to-v5"));
        MigrationResult v4 = MigrationTestSupport.migrateExistingDatabaseTo(database, "4");
        v4.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,10000,1,true)");
        long budgetId = v4.queryLong("select id from budgets where household_id=1 and period_month='2026-09'");
        v4.executeUpdate("insert into budget_revisions "
                + "(household_id,budget_id,old_amount_cents,new_amount_cents,changed_by,changed_at) values "
                + "(1," + budgetId + ",9000,10000,1,current_timestamp)");

        MigrationResult v5 = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(v5.version()).isEqualTo("5");
        assertThat(v5.queryString("select old_period_month from budget_revisions where budget_id=" + budgetId))
                .isEqualTo("2026-09");
        assertThat(v5.queryString("select new_scope_type from budget_revisions where budget_id=" + budgetId))
                .isEqualTo("TOTAL");
        assertThat(v5.queryString("select cast(old_active as varchar) from budget_revisions where budget_id=" + budgetId))
                .isEqualTo("TRUE");

        v5.executeUpdate("update budgets set active=false where id=" + budgetId);
        v5.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,11000,1,false)");
        assertThat(v5.queryLong("select count(*) from budgets where household_id=1 and period_month='2026-09'"))
                .isEqualTo(2);
        v5.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,12000,1,true)");
        assertThatThrownBy(() -> v5.executeUpdate("insert into budgets "
                + "(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) "
                + "values (1,'2026-09','TOTAL',null,null,13000,1,true)"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }
}
