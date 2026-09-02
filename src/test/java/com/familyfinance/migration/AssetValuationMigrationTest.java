package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetValuationMigrationTest {

    @TempDir Path tempDir;

    @Test
    void freshDatabaseIncludesPersistedFetchTimeAndLatestValuationIndex() {
        MigrationResult migrated = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("fresh-v9"));

        assertThat(migrated.version()).isEqualTo("9");
        assertThat(migrated.queryLong("""
                select count(*)
                from information_schema.columns
                where table_schema='PUBLIC'
                  and table_name='ASSET_VALUATIONS'
                  and column_name='FETCHED_AT'
                  and is_nullable='NO'
                """)).isEqualTo(1);
        assertThat(migrated.queryLong("""
                select count(*)
                from information_schema.index_columns
                where table_schema='PUBLIC'
                  and table_name='ASSET_VALUATIONS'
                  and index_name='IX_ASSET_VALUATIONS_ASSET_LATEST'
                  and column_name in ('ASSET_ID','VALUED_ON','FETCHED_AT','ID')
                """)).isEqualTo(4);
        assertThat(migrated.queryString("""
                select listagg(column_name || ':' || ordering_specification, ',')
                       within group (order by ordinal_position)
                from information_schema.index_columns
                where table_schema='PUBLIC'
                  and table_name='ASSET_VALUATIONS'
                  and index_name='IX_ASSET_VALUATIONS_ASSET_LATEST'
                """)).isEqualTo("ASSET_ID:ASC,VALUED_ON:DESC,FETCHED_AT:DESC,ID:DESC");
        assertThat(migrated.queryLong("""
                select count(*) from information_schema.indexes
                where table_schema='PUBLIC'
                  and index_name='IX_ASSET_VALUATIONS_HOUSEHOLD_ASSET_DATE'
                """)).isZero();
    }

    @Test
    void v8UpgradeBackfillsExistingValuationFetchTimeWithoutChangingItsFacts() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("v8-valuations"));
        MigrationResult v8 = MigrationTestSupport.migrateExistingDatabaseTo(database, "8");
        v8.executeUpdate("""
                insert into assets
                    (id,household_id,name,asset_type,owner_member_id,acquired_on,
                     purchase_value_cents,current_value_cents,status,created_by,archived_at)
                values (800,1,'迁移房产','PROPERTY',1,date '2024-01-01',50000000,55000000,'ACTIVE',1,null)
                """);
        v8.executeUpdate("""
                insert into property_assets
                    (asset_id,household_id,asset_type,address,area_sqm,usage_type)
                values (800,1,'PROPERTY','迁移地址',88.25,'SELF_USE')
                """);
        v8.executeUpdate("""
                insert into asset_valuations
                    (id,household_id,asset_id,valued_on,value_cents,source,note,created_by)
                values (800,1,800,date '2026-08-31',55000000,'MANUAL','迁移前估值',1)
                """);

        MigrationResult migrated = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(migrated.version()).isEqualTo("9");
        assertThat(migrated.queryLong("select value_cents from asset_valuations where id=800"))
                .isEqualTo(55_000_000L);
        assertThat(migrated.queryString("select note from asset_valuations where id=800"))
                .isEqualTo("迁移前估值");
        assertThat(migrated.queryString("select cast(fetched_at as varchar) from asset_valuations where id=800"))
                .isNotBlank();
    }
}
