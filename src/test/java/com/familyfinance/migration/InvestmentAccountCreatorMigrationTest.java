package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestmentAccountCreatorMigrationTest {

    @TempDir Path tempDir;

    @Test
    void v10AddsAndBackfillsHouseholdBoundInvestmentAccountCreator() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("v9-investment-account"));
        MigrationResult v9 = MigrationTestSupport.migrateExistingDatabaseTo(database, "9");
        v9.executeUpdate("""
                insert into investment_accounts (id,household_id,name,broker_name,currency,archived_at)
                values (910,1,'迁移投资账户','迁移券商','CNY',null)
                """);

        MigrationResult migrated = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(migrated.version()).isEqualTo("12");
        assertThat(migrated.queryLong("select created_by from investment_accounts where id=910"))
                .isEqualTo(1L);
        assertThat(migrated.queryLong("""
                select count(*) from information_schema.columns
                where table_schema='PUBLIC' and table_name='INVESTMENT_ACCOUNTS'
                  and column_name='CREATED_BY' and is_nullable='NO'
                """)).isEqualTo(1L);
    }

    @Test
    void accountCreatorMustBelongToItsHousehold() {
        MigrationResult migrated = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("fresh-v11"));
        migrated.executeUpdate("""
                insert into households (id,name,created_at,status) values
                    (100,'投资家庭一',current_timestamp,'ACTIVE'),
                    (101,'投资家庭二',current_timestamp,'ACTIVE')
                """);
        migrated.executeUpdate("""
                insert into app_users
                    (id,household_id,username,email,display_name,password_hash,status,created_at)
                values (100,100,'invest-one','invest-one@example.com','投资一','x','ACTIVE',current_timestamp),
                       (101,101,'invest-two','invest-two@example.com','投资二','x','ACTIVE',current_timestamp)
                """);

        assertThatThrownBy(() -> migrated.executeUpdate("""
                insert into investment_accounts
                    (id,household_id,name,broker_name,currency,archived_at,created_by)
                values (100,100,'越界创建者','测试券商','CNY',null,101)
                """))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasStackTraceContaining("FK_INVESTMENT_ACCOUNTS_CREATOR_HOUSEHOLD");
    }
}
