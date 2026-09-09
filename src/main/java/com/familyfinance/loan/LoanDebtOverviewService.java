package com.familyfinance.loan;

import com.familyfinance.family.CurrentMembership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Aggregated debt overview for all ACTIVE loans of the current household (read-only). */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LoanDebtOverviewService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final CurrentMembership current;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LoanDebtOverviewService(CurrentMembership current, JdbcTemplate jdbc, Clock clock) {
        this.current = current;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LoanDebtOverviewResponse overview(Authentication authentication) {
        long household = current.require(authentication).householdId();
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        LocalDate horizon = today.plusDays(30);

        BigDecimal[] loan = jdbc.query(
                "select coalesce(sum(current_principal_amount),0),"
                        + " coalesce(sum(current_principal_amount * annual_rate),0),"
                        + " coalesce(sum(current_principal_amount),0), count(*) "
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

        // 累计已还现金 = 已确认期次实付 + 提前还款/一次结清的额外现金（不被任何期次引用，避免重复）。
        // 按全家庭口径统计（含已结清/归档贷款的历史还款），
        // 否则刚还清一笔贷款后它会离开 ACTIVE 集合导致该指标“不涨反不动”。
        BigDecimal paidInstallments = jdbc.queryForObject(
                "select coalesce(sum(case when i.status='PAID' then"
                        + " (case when t.amount_cents is not null then t.amount_cents/100.0"
                        + " else i.principal_amount + i.interest_amount end)"
                        + " else 0 end),0) from loan_installments i"
                        + " left join financial_transactions t on t.id=i.confirmed_transaction_id"
                        + " and t.household_id=i.household_id"
                        + " where i.household_id=?",
                BigDecimal.class, household);
        BigDecimal paidPrepayments = jdbc.queryForObject(
                "select coalesce(sum(case when t.amount_cents is not null"
                        + " then cast(t.amount_cents as decimal(21,2))/100"
                        + " else p.amount + p.interest_amount end),0)"
                        + " from loan_prepayments p"
                        + " left join financial_transactions t on t.id=p.transaction_id"
                        + " and t.household_id=p.household_id"
                        + " where p.household_id=?"
                        + " and not exists (select 1 from loan_installments ci"
                        + " where ci.household_id=p.household_id"
                        + " and ci.confirmed_transaction_id=t.id)",
                BigDecimal.class, household);
        BigDecimal paid = paidInstallments.add(paidPrepayments);

        Object[] overdue = jdbc.query(
                "select count(*), coalesce(sum(principal_amount + interest_amount),0), min(due_on)"
                        + " from loan_installments"
                        + " where household_id=? and status='PENDING' and due_on<?"
                        + " and loan_id in (select id from loans where household_id=? and status='ACTIVE')",
                rs -> {
                    if (!rs.next()) return new Object[]{0, BigDecimal.ZERO, null};
                    return new Object[]{rs.getInt(1), rs.getBigDecimal(2), rs.getObject(3, LocalDate.class)};
                }, household, today, household);
        int overdueInstallments = (Integer) overdue[0];
        BigDecimal overdueAmount = (BigDecimal) overdue[1];
        LocalDate oldestDue = (LocalDate) overdue[2];
        int overdueDays = oldestDue == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(oldestDue, today);

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
                nextDueOn,
                overdueInstallments,
                plain(overdueAmount),
                overdueDays);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
