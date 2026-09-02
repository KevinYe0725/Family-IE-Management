package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetInvestmentMigrationTest {

    private static final long MAX_CENTS = 99_999_999_999L;

    @TempDir Path tempDir;

    @Test
    void freshAndV7DatabasesMigrateToV8WithoutChangingLedgerMembershipBudgetOrRecurringFacts() {
        MigrationResult fresh = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("fresh-v8"));

        assertThat(fresh.version()).isEqualTo("8");
        assertThat(fresh.tables()).contains(
                "ASSETS",
                "PROPERTY_ASSETS",
                "VEHICLE_ASSETS",
                "ASSET_VALUATIONS",
                "INVESTMENT_ACCOUNTS",
                "SECURITIES",
                "INVESTMENT_TRADES",
                "MARKET_PRICE_SNAPSHOTS",
                "MANUAL_PRICE_OVERRIDES");

        Path database = StageOneDatabaseFixture.create(tempDir.resolve("v7-upgrade"));
        MigrationResult v7 = MigrationTestSupport.migrateExistingDatabaseTo(database, "7");
        seedPlanTwoFacts(v7);
        List<List<String>> transactionsBefore = rows(v7, "select id,household_id,member_id,category_id,kind,"
                + "amount_cents,occurred_on,merchant,location,note,created_at,updated_at,account_id,"
                + "created_by_user_id,source_type,source_id from financial_transactions order by id");
        List<List<String>> accountsBefore = rows(v7, "select id,household_id,name,type,currency,"
                + "opening_balance_cents,archived_at from financial_accounts order by id");
        List<List<String>> categoriesBefore = rows(v7, "select id,household_id,kind,name,color,is_default,"
                + "created_at,parent_id from categories order by id");
        List<List<String>> membershipsBefore = rows(v7, "select id,household_id,user_id,role,status,joined_at "
                + "from household_memberships order by id");
        List<List<String>> budgetsBefore = rows(v7, "select id,household_id,period_month,scope_type,category_id,"
                + "member_id,amount_cents,version,active from budgets order by id");
        List<List<String>> revisionsBefore = rows(v7, "select id,household_id,budget_id,old_period_month,"
                + "new_period_month,old_scope_type,new_scope_type,old_category_id,new_category_id,"
                + "old_member_id,new_member_id,old_amount_cents,new_amount_cents,old_active,new_active,"
                + "changed_by,changed_at from budget_revisions order by id");
        List<List<String>> rulesBefore = rows(v7, "select id,household_id,kind,amount_cents,schedule_type,"
                + "interval_value,day_of_month,next_due_on,account_id,member_id,category_id,active,created_by,"
                + "start_on,end_on,day_of_week,assigned_user_id,paused from recurring_rules order by id");
        List<List<String>> occurrencesBefore = rows(v7, "select id,household_id,rule_id,due_on,status,"
                + "confirmed_transaction_id,assigned_user_id from recurring_occurrences order by id");

        MigrationResult migrated = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(migrated.version()).isEqualTo("8");
        assertThat(rows(migrated, transactionsSql())).containsExactlyElementsOf(transactionsBefore);
        assertThat(rows(migrated, accountsSql())).containsExactlyElementsOf(accountsBefore);
        assertThat(rows(migrated, categoriesSql())).containsExactlyElementsOf(categoriesBefore);
        assertThat(rows(migrated, membershipsSql())).containsExactlyElementsOf(membershipsBefore);
        assertThat(rows(migrated, budgetsSql())).containsExactlyElementsOf(budgetsBefore);
        assertThat(rows(migrated, revisionsSql())).containsExactlyElementsOf(revisionsBefore);
        assertThat(rows(migrated, rulesSql())).containsExactlyElementsOf(rulesBefore);
        assertThat(rows(migrated, occurrencesSql())).containsExactlyElementsOf(occurrencesBefore);
    }

    @Test
    void stageOneUpgradeToV8PreservesAllLegacyLedgerFacts() {
        Path database = StageOneDatabaseFixture.create(tempDir.resolve("stage-one-v8"));
        List<List<String>> before = rowsAtUrl(MigrationTestSupport.h2Url(database),
                "select id,household_id,member_id,category_id,kind,amount_cents,occurred_on,merchant,"
                        + "location,note,created_at,updated_at from financial_transactions order by id");

        MigrationResult migrated = MigrationTestSupport.migrateExistingDatabase(database);

        assertThat(migrated.version()).isEqualTo("8");
        assertThat(rows(migrated, "select id,household_id,member_id,category_id,kind,amount_cents,occurred_on,"
                + "merchant,location,note,created_at,updated_at from financial_transactions order by id"))
                .containsExactlyElementsOf(before);
        assertThat(migrated.queryLong("select count(*) from household_memberships")).isEqualTo(1);
        assertThat(migrated.queryLong("select count(*) from financial_transactions")).isEqualTo(12);
        assertThat(migrated.queryLong("select count(*) from budgets")).isZero();
        assertThat(migrated.queryLong("select count(*) from recurring_rules")).isZero();
    }

    @Test
    void assetsEnforceSubtypeShapeHouseholdOwnershipStatusAndMoneyBounds() {
        MigrationResult result = migratedTwoHouseholds("asset-constraints");

        insertAsset(result, 1, 1, "自住房", "PROPERTY", 1L, 0L, MAX_CENTS, "ACTIVE", 1, "null");
        result.executeUpdate("insert into property_assets "
                + "(asset_id,household_id,asset_type,address,area_sqm,usage_type) values "
                + "(1,1,'PROPERTY','西湖区 1 号',88.25,'SELF_USE')");
        insertAsset(result, 2, 1, "汽车", "VEHICLE", null, 100_000L, 80_000, "ARCHIVED", 1,
                "current_timestamp");
        result.executeUpdate("insert into vehicle_assets "
                + "(asset_id,household_id,asset_type,brand_model,plate_hint,purchase_year) values "
                + "(2,1,'VEHICLE','示例车型','浙A***01',2024)");

        assertRejected(result, assetInsert(3, 1, "错误类型", "CASH", 1L, 1L, 1, "ACTIVE", 1, "null"));
        assertRejected(result, assetInsert(3, 1, "跨家庭所有者", "OTHER", 2L, 1L, 1, "ACTIVE", 1, "null"));
        assertRejected(result, assetInsert(3, 1, "跨家庭创建者", "OTHER", 1L, 1L, 1, "ACTIVE", 2, "null"));
        assertRejected(result, assetInsert(3, 1, "负购买价", "OTHER", 1L, -1L, 1, "ACTIVE", 1, "null"));
        assertRejected(result, assetInsert(3, 1, "超上限现值", "OTHER", 1L, 1L, MAX_CENTS + 1,
                "ACTIVE", 1, "null"));
        assertRejected(result, assetInsert(3, 1, "状态不匹配", "OTHER", null, 1L, 1,
                "ARCHIVED", 1, "null"));
        assertRejected(result, "insert into property_assets "
                + "(asset_id,household_id,asset_type,address,area_sqm,usage_type) values "
                + "(2,1,'PROPERTY','错误子类型',60.00,'SELF_USE')");
        assertRejected(result, "insert into vehicle_assets "
                + "(asset_id,household_id,asset_type,brand_model,purchase_year) values "
                + "(1,1,'VEHICLE','错误子类型',2024)");
        assertRejected(result, "insert into property_assets "
                + "(asset_id,household_id,asset_type,address,area_sqm,usage_type) values "
                + "(1,2,'PROPERTY','跨家庭',60.00,'SELF_USE')");
        assertRejected(result, "insert into property_assets "
                + "(asset_id,household_id,asset_type,address,area_sqm,usage_type) values "
                + "(1,1,'PROPERTY','零面积',0.00,'SELF_USE')");
    }

    @Test
    void valuationsAndManualOverridesPreserveNullableNotesAndRejectDuplicateOrCrossHouseholdFacts() {
        MigrationResult result = migratedTwoHouseholds("historical-facts");
        insertAsset(result, 1, 1, "其他资产", "OTHER", null, null, 0, "ACTIVE", 1, "null");
        insertSecurity(result, 1, "SH", "600000.SH", "浦发银行");

        result.executeUpdate("insert into asset_valuations "
                + "(id,household_id,asset_id,valued_on,value_cents,source,note,created_by) values "
                + "(1,1,1,date '2026-09-03',0,'MANUAL',null,1)");
        result.executeUpdate("insert into asset_valuations "
                + "(id,household_id,asset_id,valued_on,value_cents,source,note,created_by) values "
                + "(2,1,1,date '2026-09-03'," + MAX_CENTS + ",'PURCHASE','初始价值',1)");
        result.executeUpdate("insert into manual_price_overrides "
                + "(id,household_id,security_id,price_cents,effective_on,note,created_by) values "
                + "(1,1,1,1,date '2026-09-03',null,1)");

        assertThat(result.queryLong("select count(*) from asset_valuations where note is null")).isEqualTo(1);
        assertThat(result.queryLong("select count(*) from manual_price_overrides where note is null")).isEqualTo(1);
        assertRejected(result, "insert into asset_valuations "
                + "(household_id,asset_id,valued_on,value_cents,source,created_by) values "
                + "(1,1,date '2026-09-03',1,'MANUAL',1)");
        assertRejected(result, "insert into asset_valuations "
                + "(household_id,asset_id,valued_on,value_cents,source,created_by) values "
                + "(2,1,date '2026-09-04',1,'MANUAL',2)");
        assertRejected(result, "insert into asset_valuations "
                + "(household_id,asset_id,valued_on,value_cents,source,created_by) values "
                + "(1,1,date '2026-09-04',1,'ESTIMATE',1)");
        assertRejected(result, "insert into asset_valuations "
                + "(household_id,asset_id,valued_on,value_cents,source,created_by) values "
                + "(1,1,date '2026-09-04',-1,'MANUAL',1)");
        assertRejected(result, "insert into manual_price_overrides "
                + "(household_id,security_id,price_cents,effective_on,created_by) values "
                + "(1,1,2,date '2026-09-03',1)");
        assertRejected(result, "insert into manual_price_overrides "
                + "(household_id,security_id,price_cents,effective_on,created_by) values "
                + "(1,1,2,date '2026-09-04',2)");
        assertRejected(result, "insert into manual_price_overrides "
                + "(household_id,security_id,price_cents,effective_on,created_by) values "
                + "(1,1,2,date '2026-09-03',1)");
        assertRejected(result, "delete from assets where id=1");
        assertRejected(result, "delete from securities where id=1");
    }

    @Test
    void investmentsEnforceAShareIdentityTradeShapesSourceKeysAndDecimalScale() {
        MigrationResult result = migratedTwoHouseholds("investment-constraints");
        insertInvestmentAccount(result, 1, 1, "证券账户", "示例券商");
        insertInvestmentAccount(result, 2, 2, "外部账户", "外部券商");
        insertSecurity(result, 1, "SH", "600000.SH", "浦发银行");
        insertSecurity(result, 2, "SZ", "000001.SZ", "平安银行");

        insertTrade(result, 1, 1, 1, 1, "BUY", "999999999999999.9999", 1, MAX_CENTS,
                "2026-09-03", 1, "MANUAL", null);
        insertTrade(result, 2, 1, 1, 1, "SELL", "0.0001", MAX_CENTS, 0,
                "2026-09-04", 1, "IMPORT", "broker-2");
        insertTrade(result, 3, 1, 1, 1, "DIVIDEND", null, 500, 0,
                "2026-09-05", 1, "MANUAL", null);
        insertTrade(result, 4, 1, 1, 1, "FEE", null, 200, 0,
                "2026-09-06", 1, "MANUAL", null);

        assertThat(decimal(result, "select quantity from investment_trades where id=1"))
                .isEqualByComparingTo("999999999999999.9999");
        assertThat(result.queryLong("select count(*) from investment_trades where id in (3,4) and quantity is null"))
                .isEqualTo(2);
        assertRejected(result, tradeInsert(5, 1, 2, 1, "BUY", "1.0000", 1, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", null, 1, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "0.0000", 1, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "DIVIDEND", "1.0000", 500, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "SPLIT", "1.0000", 1, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1000000000000000.0000", 1, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 0, 0,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 1, -1,
                "2026-09-07", 1, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 1, 0,
                "2026-09-07", 2, "MANUAL", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 1, 0,
                "2026-09-07", 1, "IMPORT", null));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 1, 0,
                "2026-09-07", 1, "MANUAL", "unexpected"));
        assertRejected(result, tradeInsert(5, 1, 1, 1, "BUY", "1.0000", 1, 0,
                "2026-09-07", 1, "IMPORT", "broker-2"));

        assertRejected(result, "insert into securities (market,ts_code,name,security_type,active) "
                + "values ('SH','600000.SH','重复证券','STOCK',true)");
        assertRejected(result, "insert into securities (market,ts_code,name,security_type,active) "
                + "values ('SZ','600000.SH','市场后缀不匹配','STOCK',true)");
        assertRejected(result, "insert into securities (market,ts_code,name,security_type,active) "
                + "values ('HK','000001.HK','非A股','STOCK',true)");
        assertRejected(result, "insert into securities (market,ts_code,name,security_type,active) "
                + "values ('SZ','00001.SZ','位数错误','STOCK',true)");
        assertRejected(result, "insert into securities (market,ts_code,name,security_type,active) "
                + "values ('SZ','000002.SZ','类型错误','FUND',true)");
    }

    @Test
    void marketSnapshotsEnforceNaturalKeySourcePriceRelationshipsAndPercentagePrecision() {
        MigrationResult result = migratedTwoHouseholds("market-constraints");
        insertSecurity(result, 1, "BJ", "430047.BJ", "诺思兰德");

        result.executeUpdate(snapshotInsert(1, "2026-09-03", 100, 120, 90, 110, 95,
                "99999.9999", "TUSHARE"));
        assertThat(decimal(result, "select pct_change from market_price_snapshots where id=1"))
                .isEqualByComparingTo("99999.9999");
        assertRejected(result, snapshotInsert(2, "2026-09-03", 100, 120, 90, 110, 95,
                "1.0000", "TUSHARE"));
        assertRejected(result, snapshotInsert(2, "2026-09-04", 100, 120, 90, 110, 95,
                "1.0000", "MANUAL"));
        assertRejected(result, snapshotInsert(2, "2026-09-04", 100, 80, 90, 110, 95,
                "1.0000", "TUSHARE"));
        assertRejected(result, snapshotInsert(2, "2026-09-04", -1, 120, 90, 110, 95,
                "1.0000", "TUSHARE"));
        assertRejected(result, snapshotInsert(2, "2026-09-04", 100, 120, 90, MAX_CENTS + 1, 95,
                "1.0000", "TUSHARE"));
        assertRejected(result, snapshotInsert(2, "2026-09-04", 100, 120, 90, 110, 95,
                "100000.0000", "TUSHARE"));
    }

    @Test
    void schemaPublishesExplicitDecimalTypesAndHouseholdStatusDateIndexes() {
        MigrationResult result = MigrationTestSupport.migrateFreshDatabase(tempDir.resolve("metadata"));

        assertColumn(result, "PROPERTY_ASSETS", "AREA_SQM", "NUMERIC", 12, 2);
        assertColumn(result, "INVESTMENT_TRADES", "QUANTITY", "NUMERIC", 19, 4);
        assertColumn(result, "MARKET_PRICE_SNAPSHOTS", "PCT_CHANGE", "NUMERIC", 9, 4);
        assertThat(indexNames(result)).contains(
                "IX_ASSETS_HOUSEHOLD_STATUS",
                "IX_ASSET_VALUATIONS_HOUSEHOLD_ASSET_DATE",
                "IX_INVESTMENT_ACCOUNTS_HOUSEHOLD_ARCHIVED",
                "IX_INVESTMENT_TRADES_HOUSEHOLD_ACCOUNT_DATE",
                "IX_INVESTMENT_TRADES_HOUSEHOLD_SECURITY_DATE",
                "IX_MARKET_PRICE_SNAPSHOTS_SECURITY_DATE",
                "IX_MANUAL_PRICE_OVERRIDES_HOUSEHOLD_SECURITY_DATE");
    }

    private MigrationResult migratedTwoHouseholds(String name) {
        Path database = StageOneDatabaseFixture.createWithSecondHousehold(tempDir.resolve(name));
        return MigrationTestSupport.migrateExistingDatabase(database);
    }

    private static void seedPlanTwoFacts(MigrationResult v7) {
        long accountId = v7.queryLong("select id from financial_accounts where household_id=1");
        v7.executeUpdate("insert into budgets "
                + "(id,household_id,period_month,scope_type,amount_cents,version,active) "
                + "values (1,1,'2026-09','TOTAL',10000,1,true)");
        v7.executeUpdate("insert into budget_revisions "
                + "(id,household_id,budget_id,old_amount_cents,new_amount_cents,changed_by,changed_at,"
                + "old_period_month,new_period_month,old_scope_type,new_scope_type,old_category_id,"
                + "new_category_id,old_member_id,new_member_id,old_active,new_active) values "
                + "(1,1,1,9000,10000,1,timestamp with time zone '2026-09-02 00:00:00+00',"
                + "'2026-09','2026-09','TOTAL','TOTAL',null,null,null,null,true,true)");
        v7.executeUpdate("insert into recurring_rules "
                + "(id,household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                + "account_id,member_id,category_id,active,created_by,start_on,end_on,day_of_week,"
                + "assigned_user_id,paused) values (1,1,'EXPENSE',500,'MONTHLY',1,15,date '2026-10-15',"
                + accountId + ",1,1,true,1,date '2026-09-15',null,null,1,false)");
        v7.executeUpdate("insert into recurring_occurrences "
                + "(id,household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id) "
                + "values (1,1,1,date '2026-09-15','PENDING',null,1)");
    }

    private static String transactionsSql() {
        return "select id,household_id,member_id,category_id,kind,amount_cents,occurred_on,merchant,location,"
                + "note,created_at,updated_at,account_id,created_by_user_id,source_type,source_id "
                + "from financial_transactions order by id";
    }

    private static String membershipsSql() {
        return "select id,household_id,user_id,role,status,joined_at from household_memberships order by id";
    }

    private static String accountsSql() {
        return "select id,household_id,name,type,currency,opening_balance_cents,archived_at "
                + "from financial_accounts order by id";
    }

    private static String categoriesSql() {
        return "select id,household_id,kind,name,color,is_default,created_at,parent_id from categories order by id";
    }

    private static String budgetsSql() {
        return "select id,household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active "
                + "from budgets order by id";
    }

    private static String revisionsSql() {
        return "select id,household_id,budget_id,old_period_month,new_period_month,old_scope_type,new_scope_type,"
                + "old_category_id,new_category_id,old_member_id,new_member_id,old_amount_cents,new_amount_cents,"
                + "old_active,new_active,changed_by,changed_at from budget_revisions order by id";
    }

    private static String rulesSql() {
        return "select id,household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                + "account_id,member_id,category_id,active,created_by,start_on,end_on,day_of_week,assigned_user_id,"
                + "paused from recurring_rules order by id";
    }

    private static String occurrencesSql() {
        return "select id,household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id "
                + "from recurring_occurrences order by id";
    }

    private static void insertAsset(
            MigrationResult result,
            long id,
            long householdId,
            String name,
            String type,
            Long owner,
            Long purchase,
            long current,
            String status,
            long creator,
            String archivedAt) {
        result.executeUpdate(assetInsert(id, householdId, name, type, owner, purchase, current, status, creator,
                archivedAt));
    }

    private static String assetInsert(
            long id,
            long householdId,
            String name,
            String type,
            Long owner,
            Long purchase,
            long current,
            String status,
            long creator,
            String archivedAt) {
        return "insert into assets (id,household_id,name,asset_type,owner_member_id,acquired_on,"
                + "purchase_value_cents,current_value_cents,status,created_by,archived_at) values ("
                + id + "," + householdId + ",'" + name + "','" + type + "'," + sql(owner)
                + ",null," + sql(purchase) + "," + current + ",'" + status + "'," + creator + ","
                + archivedAt + ")";
    }

    private static void insertInvestmentAccount(
            MigrationResult result, long id, long householdId, String name, String broker) {
        result.executeUpdate("insert into investment_accounts "
                + "(id,household_id,name,broker_name,currency,archived_at) values ("
                + id + "," + householdId + ",'" + name + "','" + broker + "','CNY',null)");
    }

    private static void insertSecurity(MigrationResult result, long id, String market, String code, String name) {
        result.executeUpdate("insert into securities (id,market,ts_code,name,security_type,active) values ("
                + id + ",'" + market + "','" + code + "','" + name + "','STOCK',true)");
    }

    private static void insertTrade(
            MigrationResult result,
            long id,
            long household,
            long account,
            long security,
            String type,
            String quantity,
            long price,
            long fee,
            String tradedOn,
            long creator,
            String source,
            String sourceId) {
        result.executeUpdate(tradeInsert(id, household, account, security, type, quantity, price, fee, tradedOn,
                creator, source, sourceId));
    }

    private static String tradeInsert(
            long id,
            long household,
            long account,
            long security,
            String type,
            String quantity,
            long price,
            long fee,
            String tradedOn,
            long creator,
            String source,
            String sourceId) {
        return "insert into investment_trades (id,household_id,account_id,security_id,trade_type,quantity,"
                + "price_cents,fee_cents,traded_on,created_by,source_type,source_id) values ("
                + id + "," + household + "," + account + "," + security + ",'" + type + "',"
                + (quantity == null ? "null" : quantity) + "," + price + "," + fee + ",date '" + tradedOn
                + "'," + creator + ",'" + source + "'," + sql(sourceId) + ")";
    }

    private static String snapshotInsert(
            long id,
            String date,
            long open,
            long high,
            long low,
            long close,
            long preClose,
            String pct,
            String source) {
        return "insert into market_price_snapshots (id,security_id,trade_date,open_cents,high_cents,low_cents,"
                + "close_cents,pre_close_cents,pct_change,source,fetched_at) values (" + id
                + ",1,date '" + date + "'," + open + "," + high + "," + low + "," + close + ","
                + preClose + "," + pct + ",'" + source + "',timestamp with time zone '2026-09-03 10:00:00+00')";
    }

    private static String sql(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "'" + text.replace("'", "''") + "'";
        return value.toString();
    }

    private static BigDecimal decimal(MigrationResult result, String query) {
        try (Connection connection = DriverManager.getConnection(result.databaseUrl(), "sa", "");
                ResultSet rows = connection.createStatement().executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getBigDecimal(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not query decimal", exception);
        }
    }

    private static void assertColumn(
            MigrationResult result, String table, String column, String type, long precision, long scale) {
        String sql = "select data_type,numeric_precision,numeric_scale from information_schema.columns "
                + "where table_schema='PUBLIC' and table_name='" + table + "' and column_name='" + column + "'";
        try (Connection connection = DriverManager.getConnection(result.databaseUrl(), "sa", "");
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("data_type")).isEqualTo(type);
            assertThat(rows.getLong("numeric_precision")).isEqualTo(precision);
            assertThat(rows.getLong("numeric_scale")).isEqualTo(scale);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect column metadata", exception);
        }
    }

    private static List<String> indexNames(MigrationResult result) {
        String sql = "select distinct index_name from information_schema.index_columns "
                + "where table_schema='PUBLIC' and index_name is not null order by index_name";
        try (Connection connection = DriverManager.getConnection(result.databaseUrl(), "sa", "");
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            List<String> names = new ArrayList<>();
            while (rows.next()) names.add(rows.getString(1));
            return names;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect indexes", exception);
        }
    }

    private static List<List<String>> rows(MigrationResult result, String sql) {
        return rowsAtUrl(result.databaseUrl(), sql);
    }

    private static List<List<String>> rowsAtUrl(String url, String sql) {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                ResultSet resultSet = connection.createStatement().executeQuery(sql)) {
            List<List<String>> allRows = new ArrayList<>();
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    Object value = resultSet.getObject(column);
                    row.add(value == null ? null : value.toString());
                }
                allRows.add(Collections.unmodifiableList(row));
            }
            return List.copyOf(allRows);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not snapshot migration rows: " + sql, exception);
        }
    }

    private static void assertRejected(MigrationResult result, String sql) {
        assertThatThrownBy(() -> result.executeUpdate(sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not execute migration test statement");
    }
}
