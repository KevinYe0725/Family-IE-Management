package com.familyfinance.loan;

import com.familyfinance.family.CurrentMembership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregated debt overview for all ACTIVE loans of the current household (read-only). */
@Service
@Transactional(readOnly = true)
public class LoanDebtOverviewService {
    private final CurrentMembership current;
    private final JdbcTemplate jdbc;

    public LoanDebtOverviewService(CurrentMembership current, JdbcTemplate jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    public LoanDebtOverviewResponse overview(Authentication authentication) {
        long household = current.require(authentication).householdId();
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(30);

        BigDecimal[] loan = jdbc.query(
                "select coalesce(sum(current_principal_amount),0),"
                        + " coalesce(sum(principal_amount * annual_rate),0),"
                        + " coalesce(sum(principal_amount),0), count(*) "
                        + "from loans where household_id=? and status='ACTIVE'",
                rs -> rs.next()
                        ? new BigDecimal[]{
                            rs.getBigDecimal(1),
                            rs.getBigDecimal(2),
                            rs.getBigDecimal(3),
                            BigDecimal.valueOf(rs.getLong(4))}
                        : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO},
                household);
        BigDecimal principal = loan[0];
        BigDecimal weightedBase = loan[2];
        int count = loan[3].intValue();

        BigDecimal remaining = jdbc.queryForObject(
                "select coalesce(sum(principal_amount + interest_amount),0) from loan_installments"
                        + " where household_id=? and status='PENDING'"
                        + " and loan_id in (select id from loans where household_id=? and status='ACTIVE')",
                BigDecimal.class, household, household);

        BigDecimal thirtyDay = jdbc.queryForObject(
                "select coalesce(sum(principal_amount + interest_amount),0) from loan_installments"
                        + " where household_id=? and status='PENDING' and due_on>=? and due_on<?"
                        + " and loan_id in (select id from loans where household_id=? and status='ACTIVE')",
                BigDecimal.class, household, today, horizon, household);

        LocalDate nextDueOn = jdbc.query(
                "select min(due_on) from loan_installments where household_id=? and status='PENDING'"
                        + " and loan_id in (select id from loans where household_id=? and status='ACTIVE')",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, household, household);

        BigDecimal paid = jdbc.queryForObject(
                "select coalesce(sum(case when i.status='PAID' then"
                        + " (case when i.confirmed_transaction_id is not null then t.amount_cents else 0 end)/100.0"
                        + " else 0 end),0) from loan_installments i"
                        + " left join financial_transactions t on t.id=i.confirmed_transaction_id"
                        + " where i.household_id=? and i.loan_id in"
                        + " (select id from loans where household_id=? and status='ACTIVE')",
                BigDecimal.class, household, household);

        BigDecimal weighted = weightedBase != null && weightedBase.signum() > 0
                ? loan[1].multiply(new BigDecimal("100")).divide(weightedBase, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new LoanDebtOverviewResponse(
                count,
                plain(principal),
                plain(remaining),
                plain(thirtyDay),
                plain(paid),
                weighted.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                nextDueOn);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
