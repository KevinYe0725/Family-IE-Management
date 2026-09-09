package com.familyfinance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=true")
@ActiveProfiles("test")
@Transactional
class FinancialAccountReferenceGuardTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FinancialAccountReferenceGuard guard;

    @Test
    void historicalAssetAndLoanDisbursementDoNotBlockButActiveLoanPaymentDoes() {
        long household = household();
        long actor = jdbc.queryForObject("select min(id) from app_users where household_id=?", Long.class, household);
        long member = jdbc.queryForObject("select min(id) from family_members where household_id=?", Long.class, household);
        long category = jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'", Long.class, household);
        long historical = account(household, "历史来源");
        long payment = account(household, "当前付款");

        jdbc.update("""
                insert into assets(household_id,name,asset_type,current_value_cents,status,created_by,
                    accounting_mode,accounting_on,initial_value_cents,funding_account_id,last_accounting_on)
                values (?,?,'OTHER',0,'ACTIVE',?,'PURCHASE',date '2026-01-01',0,?,date '2026-01-01')
                """, household, "历史资产-" + UUID.randomUUID(), actor, historical);
        jdbc.update("""
                insert into loans(household_id,name,loan_type,member_id,assigned_user_id,payment_account_id,
                    payment_category_id,principal_amount,annual_rate,term_months,repayment_method,start_on,
                    current_principal_amount,status,created_by,funding_mode,accounting_on,disbursement_account_id)
                values (?,?, 'OTHER',?,?,?,?,100.00,0.1,1,'EQUAL_PAYMENT',date '2026-01-01',
                    100.00,'ACTIVE',?,'DISBURSEMENT',date '2026-01-01',?)
                """, household, "历史放款-" + UUID.randomUUID(), member, actor, payment, category, actor, historical);

        assertThat(guard.hasBlockingReferences(household, historical)).isFalse();
        jdbc.update("update loans set payment_account_id=? where household_id=?", historical, household);
        assertThat(guard.hasBlockingReferences(household, historical)).isTrue();
    }

    @Test
    void activeFundingAndRecurringReferencesBlockUntilArchivedOrInactive() {
        long household = household();
        long actor = jdbc.queryForObject("select min(id) from app_users where household_id=?", Long.class, household);
        long member = jdbc.queryForObject("select min(id) from family_members where household_id=?", Long.class, household);
        long category = jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'", Long.class, household);
        long account = account(household, "有绑定");

        jdbc.update("""
                insert into investment_accounts(household_id,name,broker_name,currency,created_by,funding_account_id)
                values (?,?, '测试券商','CNY',?,?)
                """, household, "投资绑定-" + UUID.randomUUID(), actor, account);
        assertThat(guard.hasBlockingReferences(household, account)).isTrue();
        jdbc.update("update investment_accounts set archived_at=current_timestamp where household_id=?", household);
        assertThat(guard.hasBlockingReferences(household, account)).isFalse();

        jdbc.update("""
                insert into recurring_rules(household_id,kind,amount_cents,schedule_type,interval_value,
                    day_of_month,next_due_on,account_id,member_id,category_id,active,created_by)
                values (?, 'EXPENSE', 100, 'MONTHLY', 1, 15, date '2026-09-15', ?, ?, ?, true, ?)
                """, household, account, member, category, actor);
        assertThat(guard.hasBlockingReferences(household, account)).isTrue();
        jdbc.update("update recurring_rules set active=false where household_id=? and account_id=?", household, account);
        assertThat(guard.hasBlockingReferences(household, account)).isFalse();
    }

    private long household() {
        return jdbc.queryForObject("select min(id) from households", Long.class);
    }

    private long account(long household, String prefix) {
        String name = prefix + "-" + UUID.randomUUID();
        jdbc.update("insert into financial_accounts(household_id,name,type,currency,opening_balance_cents) values (?,?,'BANK','CNY',0)", household, name);
        return jdbc.queryForObject("select id from financial_accounts where household_id=? and name=?", Long.class, household, name);
    }
}
