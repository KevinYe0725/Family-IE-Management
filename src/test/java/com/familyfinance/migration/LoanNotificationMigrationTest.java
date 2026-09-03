package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanNotificationMigrationTest {

    @TempDir Path tempDir;

    @Test
    void v11AddsHouseholdScopedLoanReminderAndSnapshotSchema() {
        MigrationResult migrated = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("fresh-v11"));
        assertThat(migrated.version()).isEqualTo("12");
        assertThat(migrated.tables()).contains("LOANS", "LOAN_INSTALLMENTS", "NOTIFICATIONS", "NET_WORTH_SNAPSHOTS");
        assertThat(migrated.queryLong("""
                select count(*) from information_schema.table_constraints
                 where table_schema='PUBLIC' and constraint_name='UK_LOAN_INSTALLMENTS_LOAN_NUMBER'
                """)).isEqualTo(1L);
    }
}
