package com.familyfinance.accounting;

import com.familyfinance.shared.DecimalMoney;
import com.familyfinance.shared.RequestValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A read-only view of the cash legs of current sources, not a second transaction store. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CashMovementService {
    private static final String CASH_LEGS = """
            select j.id journal_id, s.source_type, s.source_id, j.effective_on,
                   a.id account_id, a.name account_name, a.currency,
                   sum(e.debit_amount-e.credit_amount) net_amount
            from ledger_sources s
            join ledger_journals j on j.id=s.current_journal_id and j.household_id=s.household_id
                 and j.source_type=s.source_type and j.source_id=s.source_id
            join ledger_entries e on e.journal_id=j.id and e.household_id=j.household_id
            join ledger_accounts l on l.household_id=e.household_id and l.account_code=e.account_code
                 and l.kind='CASH' and l.currency=e.currency
            join financial_accounts a on a.household_id=e.household_id
                 and e.account_code=concat('CASH:',a.id) and a.currency=e.currency
            where s.household_id=?
            """;
    private static final String GROUP_CASH_LEGS = """
             group by j.id, s.source_type, s.source_id, j.effective_on, a.id, a.name, a.currency
             having sum(e.debit_amount-e.credit_amount)
            """;
    private final JdbcTemplate jdbc;

    public CashMovementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CashMovementResponse.Page page(long householdId, String month, Long accountId, Long bankAccountId,
                                          String kind, int page, int size) {
        YearMonth period = month(month);
        String direction = kind(kind);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(50, Math.max(1, size));
        long offset = (long) safePage * safeSize;
        StringBuilder query = new StringBuilder(CASH_LEGS);
        List<Object> arguments = new ArrayList<>();
        arguments.add(householdId);
        if (period != null) {
            query.append(" and j.effective_on>=? and j.effective_on<=?");
            arguments.add(period.atDay(1));
            arguments.add(period.atEndOfMonth());
        }
        if (accountId != null) {
            query.append(" and a.id=?");
            arguments.add(accountId);
        }
        if (bankAccountId != null) {
            query.append(" and a.bank_account_id=?");
            arguments.add(bankAccountId);
        }
        // Filter after aggregation so split lines produce one direction and one native amount.
        query.append(GROUP_CASH_LEGS).append(direction == null ? "<>0"
                : direction.equals("income") ? ">0" : "<0");
        long total = jdbc.queryForObject("select count(*) from (" + query + ") cash_movements", Long.class,
                arguments.toArray());
        arguments.add(safeSize);
        arguments.add(offset);
        var items = jdbc.query(query + " order by j.effective_on desc, j.id desc, a.id desc limit ? offset ?",
                (rs, row) -> {
                    long journalId = rs.getLong("journal_id");
                    long cashAccountId = rs.getLong("account_id");
                    String sourceType = rs.getString("source_type");
                    BigDecimal amount = rs.getBigDecimal("net_amount");
                    boolean internalTransfer = sourceType.equals("CASH_TRANSFER") || sourceType.equals("FX_TRANSFER");
                    return new CashMovementResponse(journalId + ":" + cashAccountId, journalId, sourceType,
                            rs.getLong("source_id"), rs.getObject("effective_on", LocalDate.class), cashAccountId,
                            rs.getString("account_name"), rs.getString("currency"), amount.signum() > 0 ? "income" : "expense",
                            DecimalMoney.format(amount.abs()), internalTransfer, description(sourceType, amount.signum()));
                }, arguments.toArray());
        return new CashMovementResponse.Page(List.copyOf(items), safePage, safeSize, total,
                (int) ((total + safeSize - 1) / safeSize), offset + safeSize < total);
    }

    private static YearMonth month(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new DateTimeParseException("Invalid month", value, 0);
            YearMonth result = YearMonth.parse(value);
            if (result.getYear() < 1000) throw new DateTimeParseException("Invalid year", value, 0);
            return result;
        } catch (DateTimeParseException exception) {
            throw new RequestValidationException(Map.of("month", "月份格式必须是 YYYY-MM，年份不能早于1000年"));
        }
    }

    private static String kind(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("income") && !value.equals("expense")) {
            throw new RequestValidationException(Map.of("kind", "资金方向必须为 income 或 expense"));
        }
        return value;
    }

    private static String description(String sourceType, int direction) {
        return switch (sourceType) {
            case "CASH_OPENING" -> "期初余额";
            case "TRANSACTION" -> direction > 0 ? "收入到账" : "支出付款";
            case "CASH_TRANSFER" -> "账户互转";
            case "FX_TRANSFER" -> "换汇";
            case "LOAN_DISBURSEMENT" -> "贷款到账";
            case "LOAN_FINANCED_PURCHASE" -> "贷款购置首付款";
            case "LOAN_PAYMENT" -> "贷款还款";
            case "LOAN_PREPAYMENT" -> "提前还款";
            case "ASSET_ACQUISITION" -> "资产购置";
            case "ASSET_DISPOSAL" -> "资产处置";
            case "INVESTMENT_TRADE" -> "投资交易";
            default -> "资金变动";
        };
    }
}
