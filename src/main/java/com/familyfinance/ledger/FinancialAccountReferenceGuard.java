package com.familyfinance.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current-read archive guard for cash-account references. Every probe locks only
 * one matching reference row, avoiding a repeatable-read aggregate snapshot.
 */
@Service
@Transactional
public class FinancialAccountReferenceGuard {

    private final JdbcTemplate jdbc;

    public FinancialAccountReferenceGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasBlockingReferences(long householdId, long accountId) {
        return exists("""
                select id from recurring_rules
                where household_id=? and account_id=? and active=true
                limit 1 for update
                """, householdId, accountId)
                || exists("""
                select id from investment_accounts
                where household_id=? and funding_account_id=? and archived_at is null
                limit 1 for update
                """, householdId, accountId)
                || exists("""
                select id from investment_plans
                where household_id=? and funding_account_id=? and state<>'ENDED'
                limit 1 for update
                """, householdId, accountId)
                || exists("""
                select id from investment_plan_occurrences
                where household_id=? and funding_account_id=? and state='PENDING'
                limit 1 for update
                """, householdId, accountId)
                || exists("""
                select id from loans
                where household_id=? and payment_account_id=? and status='ACTIVE'
                limit 1 for update
                """, householdId, accountId);
    }

    private boolean exists(String sql, long householdId, long accountId) {
        return !jdbc.query(sql, (rs, row) -> rs.getLong(1), householdId, accountId).isEmpty();
    }
}
