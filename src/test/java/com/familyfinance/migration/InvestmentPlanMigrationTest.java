package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestmentPlanMigrationTest {
    @TempDir Path directory;
    @Test void upgradesExistingAccountingSchemaWithoutPostingMoney() {
        Path database=directory.resolve("investment-plans");
        MigrationResult before=MigrationTestSupport.migrateFreshDatabaseTo(database,"38");
        long journals=before.queryLong("select count(*) from ledger_journals");
        MigrationResult after=MigrationTestSupport.migrateExistingDatabase(database);
        assertThat(after.version()).isEqualTo("44");
        assertThat(after.tables()).contains("INVESTMENT_PLANS","INVESTMENT_PLAN_OCCURRENCES");
        assertThat(after.queryLong("select count(*) from ledger_journals")).isEqualTo(journals);
        assertThat(after.queryLong("select count(*) from investment_plan_occurrences")).isZero();
    }

    @Test void upgradePausesLegacyPlansAndPreservesAllOccurrenceSnapshots() {
        Path database=StageOneDatabaseFixture.create(directory.resolve("legacy-plans"));
        MigrationResult before=MigrationTestSupport.migrateExistingDatabaseTo(database,"39");
        long cash=before.queryLong("select min(id) from financial_accounts where household_id=1");
        before.executeUpdate("insert into investment_accounts(id,household_id,name,broker_name,currency,created_by,funding_account_id) values(901,1,'legacy broker','broker','CNY',1,"+cash+")");
        before.executeUpdate("insert into securities(id,market,ts_code,name,security_type,active,catalog_verified) values(901,'SH','600000.SH','legacy security','STOCK',true,true)");
        String[] states={"ACTIVE","PAUSED","ENDED"};
        for(int i=0;i<states.length;i++) {
            long id=901+i;
            before.executeUpdate("insert into investment_plans(id,household_id,name,account_id,account_name,funding_account_id,security_id,security_name,symbol,currency,amount,frequency,first_due_on,next_due_on,assigned_user_id,state,created_by,updated_by,created_at,updated_at) values("+id+",1,'legacy',901,'legacy broker',"+cash+",901,'legacy security','600000','CNY',123.45,'MONTHLY','2026-01-01','2026-02-01',1,'"+states[i]+"',1,1,current_timestamp,current_timestamp)");
        }
        String columns="insert into investment_plan_occurrences(id,household_id,plan_id,plan_name,account_id,account_name,funding_account_id,security_id,security_name,symbol,currency,amount,due_on,assigned_user_id,state,remind_at,trade_id,actual_amount,acted_by,acted_at) values ";
        String common=",1,901,'legacy',901,'legacy broker',"+cash+",901,'legacy security','600000','CNY',123.45,";
        before.executeUpdate(columns+"(901"+common+"'2026-01-01',1,'PENDING',current_timestamp,null,null,null,null)");
        before.executeUpdate(columns+"(902"+common+"'2026-02-01',1,'SKIPPED',current_timestamp,null,null,1,current_timestamp)");
        before.executeUpdate(columns+"(903"+common+"'2026-03-01',1,'CONFIRMED',current_timestamp,987,101.00,1,current_timestamp)");
        long journals=before.queryLong("select count(*) from ledger_journals");
        MigrationResult after=MigrationTestSupport.migrateExistingDatabase(database);
        assertThat(after.version()).isEqualTo("44");
        assertThat(after.queryLong("select count(*) from investment_plans where state='PAUSED' and quantity is null and amount=123.45")).isEqualTo(2);
        assertThat(after.queryString("select state from investment_plans where id=903")).isEqualTo("ENDED");
        assertThat(after.queryLong("select count(*) from investment_plan_occurrences where quantity is null and amount=123.45")).isEqualTo(3);
        assertThat(after.queryLong("select count(*) from investment_plan_occurrences where actual_quantity is null")).isEqualTo(3);
        assertThat(after.queryString("select state from investment_plan_occurrences where id=901")).isEqualTo("PENDING");
        assertThat(after.queryString("select state from investment_plan_occurrences where id=902")).isEqualTo("SKIPPED");
        assertThat(after.queryLong("select count(*) from investment_plan_occurrences where id=903 and state='CONFIRMED' and trade_id=987 and actual_amount=101.00")).isEqualTo(1);
        assertThat(after.queryLong("select count(*) from ledger_journals")).isEqualTo(journals);
        assertThatThrownBy(()->after.executeUpdate("update investment_plan_occurrences set quantity=10,amount=null where id=903")).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(()->after.executeUpdate("update investment_plan_occurrences set actual_quantity=10 where id=901")).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(()->after.executeUpdate("update investment_plan_occurrences set actual_quantity=0 where id=903")).hasRootCauseInstanceOf(java.sql.SQLException.class);
        after.executeUpdate("update investment_plan_occurrences set quantity=100,amount=null,actual_quantity=10 where id=903");
        assertThat(after.queryString("select cast(actual_quantity as varchar) from investment_plan_occurrences where id=903")).isEqualTo("10.0000");
        for(String table:new String[]{"investment_plans","investment_plan_occurrences"}) {
            assertThatThrownBy(()->after.executeUpdate("update "+table+" set quantity=1 where id=901")).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->after.executeUpdate("update "+table+" set amount=null where id=901")).hasRootCauseInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->after.executeUpdate("update "+table+" set amount=null,quantity=0 where id=901")).hasRootCauseInstanceOf(java.sql.SQLException.class);
            after.executeUpdate("update "+table+" set amount=null,quantity=999999999999999.9999 where id=901");
            assertThat(after.queryString("select cast(quantity as varchar) from "+table+" where id=901")).isEqualTo("999999999999999.9999");
        }
    }
}
