package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankAccountMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void v41CreatesParentForEachLegacyBankWithoutPostingCash() {
        Path database = tempDir.resolve("legacy-bank-accounts");
        database = StageOneDatabaseFixture.create(database);
        MigrationResult before = MigrationTestSupport.migrateExistingDatabaseTo(database, "40");
        before.executeUpdate("""
                insert into financial_accounts
                    (household_id,name,type,currency,opening_balance_cents,bank_name,card_last_four)
                values
                    (1,'旧人民币卡','BANK','CNY',12345,'迁移银行','1234'),
                    (1,'旧美元卡','BANK','USD',6789,'迁移银行','5678')
                """);
        long journals = before.queryLong("select count(*) from ledger_journals");

        MigrationResult after = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(after.version()).isEqualTo("47");
        assertThat(after.queryLong("select count(*) from bank_accounts where household_id=1")).isEqualTo(2);
        assertThat(after.queryLong("select count(*) from financial_accounts where household_id=1 and type='BANK' and bank_account_id is not null")).isEqualTo(2);
        assertThat(after.queryLong("select count(*) from financial_accounts a join bank_accounts b on b.id=a.bank_account_id and b.household_id=a.household_id where a.name=b.name and a.bank_name=b.bank_name and a.card_last_four=b.card_last_four")).isEqualTo(2);
        assertThat(after.queryLong("select count(*) from ledger_journals")).isEqualTo(journals);
    }

    @Test
    void v41EnforcesHouseholdBoundParentAndOneCurrencyPerParent() {
        Path database = tempDir.resolve("bank-account-integrity");
        database = StageOneDatabaseFixture.createWithSecondHousehold(database);
        MigrationResult before = MigrationTestSupport.migrateExistingDatabaseTo(database, "40");
        before.executeUpdate("insert into financial_accounts(id,household_id,name,type,currency,opening_balance_cents) values(901,1,'主卡','BANK','CNY',0)");
        before.executeUpdate("insert into financial_accounts(id,household_id,name,type,currency,opening_balance_cents) values(902,2,'外部卡','BANK','CNY',0)");
        MigrationResult after = MigrationTestSupport.migrateExistingDatabase(database);
        long parent = after.queryLong("select bank_account_id from financial_accounts where id=901");
        long foreignParent = after.queryLong("select bank_account_id from financial_accounts where id=902");

        assertThatThrownBy(() -> after.executeUpdate("update financial_accounts set bank_account_id=" + foreignParent + " where id=901"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> after.executeUpdate("insert into financial_accounts(household_id,name,type,currency,opening_balance_cents,bank_account_id) values(1,'重复币种','BANK','CNY',0," + parent + ")"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void v41SanitizesMalformedLegacyTailDuringParentBackfill() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("malformed-legacy-tail"));
        MigrationResult before = MigrationTestSupport.migrateExistingDatabaseTo(database, "40");
        before.executeUpdate("""
                insert into financial_accounts
                    (household_id,name,type,currency,opening_balance_cents,bank_name,card_last_four)
                values (1,'旧格式尾号','BANK','CNY',0,'迁移银行','ABCD')
                """);

        MigrationResult after = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(after.queryString("select card_last_four from bank_accounts where name='旧格式尾号'")).isNull();
        assertThat(after.queryString("select card_last_four from financial_accounts where name='旧格式尾号'")).isEqualTo("ABCD");
    }
}
