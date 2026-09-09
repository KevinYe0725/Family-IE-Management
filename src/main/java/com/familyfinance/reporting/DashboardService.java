package com.familyfinance.reporting;

import com.familyfinance.accounting.LedgerReportingService;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.DecimalMoney;
import com.familyfinance.transaction.TransactionFilter;
import com.familyfinance.transaction.TransactionSummaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class DashboardService {
    private final LedgerReportingService ledger;
    private final TransactionSummaryService summaries;

    public DashboardService(LedgerReportingService ledger, TransactionSummaryService summaries) {
        this.ledger = ledger;
        this.summaries = summaries;
    }

    public DashboardResponse dashboard(long householdId, YearMonth month) {
        return dashboard(householdId, month, false);
    }

    public DashboardResponse dashboard(long householdId, YearMonth month, boolean rollupCategories) {
        var report = summaries.report(householdId,
                new TransactionFilter(month.toString(), null, null, null, null, null, null, null), rollupCategories);
        var summary = report.summary();
        var daily = new TreeMap<LocalDate, DailyTotals>();
        for (var row : summary.daily()) {
            var totals = daily.computeIfAbsent(row.date(), ignored -> new DailyTotals());
            if (row.kind() == TransactionKind.INCOME) totals.income = add(totals.income, row.amount());
            else totals.expense = add(totals.expense, row.amount());
        }
        // Additional cash fields describe all journal movements, not record-list totals.
        var flow = ledger.cashFlowAmounts(householdId, month.atDay(1), month.plusMonths(1).atDay(1));
        return new DashboardResponse(new DashboardSummaryResponse(summary.income(), summary.expense(), summary.balance(),
                format(flow.cashIn()), format(flow.cashOut()), format(flow.principalPaid()), format(flow.borrowed()),
                format(flow.noncashValuationChange())),
                daily.entrySet().stream().map(entry -> new DailyTrendResponse(entry.getKey().toString(),
                        format(entry.getValue().income), format(entry.getValue().expense))).toList(),
                summary.categories().stream().filter(row -> row.kind() == TransactionKind.EXPENSE)
                        .sorted(Comparator.comparing((com.familyfinance.transaction.TransactionSummaryResponse.CategorySummary row) ->
                                number(row.amount()), Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(row -> row.categoryId()))
                        .map(row -> new ExpenseCategoryResponse(row.categoryId(), row.name(), row.amount(),
                                percentage(number(row.amount()), number(summary.expense())))).toList(),
                report.members().stream().sorted(Comparator.comparing((TransactionSummaryService.MemberSummary row) ->
                                number(row.amount()), Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparingLong(TransactionSummaryService.MemberSummary::id))
                        .map(row -> new MemberExpenseResponse(row.id(), row.name(), row.amount())).toList());
    }

    private static BigDecimal add(BigDecimal total, String amount) {
        return total == null || amount == null ? null : total.add(new BigDecimal(amount));
    }
    private static BigDecimal number(String amount) { return amount == null ? null : new BigDecimal(amount); }
    private static String format(BigDecimal amount) { return amount == null ? null : DecimalMoney.format(amount); }
    static String formatCents(long cents) { return DecimalMoney.format(DecimalMoney.fromCents(cents)); }
    static String percentage(long numerator, long denominator) {
        return percentage(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
    }
    private static String percentage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) return null;
        if (denominator.signum() == 0) return "0.0";
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP).toPlainString();
    }
    private static final class DailyTotals {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
    }
}
