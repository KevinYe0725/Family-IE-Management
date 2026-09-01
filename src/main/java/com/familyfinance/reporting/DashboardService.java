package com.familyfinance.reporting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Sort MONTH_SORT = Sort.by(
            Sort.Order.asc("occurredOn"),
            Sort.Order.asc("id"));

    private final FinancialTransactionRepository transactionRepository;

    public DashboardService(FinancialTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public DashboardResponse dashboard(long householdId, YearMonth month) {
        List<FinancialTransaction> transactions = transactionRepository.findByHouseholdIdAndOccurredOnBetween(
                householdId,
                month.atDay(1),
                month.atEndOfMonth(),
                MONTH_SORT);

        long incomeCents = 0L;
        long expenseCents = 0L;
        Map<LocalDate, DailyTotals> dailyTotals = new TreeMap<>();
        Map<Long, NamedTotal> categoryTotals = new LinkedHashMap<>();
        Map<Long, NamedTotal> memberTotals = new LinkedHashMap<>();

        for (FinancialTransaction transaction : transactions) {
            long amountCents = transaction.getAmountCents();
            DailyTotals daily = dailyTotals.computeIfAbsent(transaction.getOccurredOn(), ignored -> new DailyTotals());
            if (transaction.getKind() == TransactionKind.INCOME) {
                incomeCents += amountCents;
                daily.incomeCents += amountCents;
            } else {
                expenseCents += amountCents;
                daily.expenseCents += amountCents;
                categoryTotals.computeIfAbsent(
                                transaction.getCategory().getId(),
                                ignored -> new NamedTotal(
                                        transaction.getCategory().getId(),
                                        transaction.getCategory().getName()))
                        .amountCents += amountCents;
                memberTotals.computeIfAbsent(
                                transaction.getMember().getId(),
                                ignored -> new NamedTotal(
                                        transaction.getMember().getId(),
                                        transaction.getMember().getName()))
                        .amountCents += amountCents;
            }
        }

        long totalExpenseCents = expenseCents;
        return new DashboardResponse(
                new DashboardSummaryResponse(
                        formatCents(incomeCents),
                        formatCents(expenseCents),
                        formatCents(incomeCents - expenseCents)),
                dailyTotals.entrySet().stream()
                        .map(entry -> new DailyTrendResponse(
                                entry.getKey().toString(),
                                formatCents(entry.getValue().incomeCents),
                                formatCents(entry.getValue().expenseCents)))
                        .toList(),
                categoryTotals.values().stream()
                        .sorted(totalOrdering())
                        .map(total -> new ExpenseCategoryResponse(
                                total.id,
                                total.name,
                                formatCents(total.amountCents),
                                percentage(total.amountCents, totalExpenseCents)))
                        .toList(),
                memberTotals.values().stream()
                        .sorted(totalOrdering())
                        .map(total -> new MemberExpenseResponse(total.id, total.name, formatCents(total.amountCents)))
                        .toList());
    }

    private static Comparator<NamedTotal> totalOrdering() {
        return Comparator.comparingLong((NamedTotal total) -> total.amountCents)
                .reversed()
                .thenComparingLong(total -> total.id);
    }

    static String formatCents(long cents) {
        if (cents < 0) {
            return "-" + formatCents(Math.negateExact(cents));
        }
        return "%d.%02d".formatted(cents / 100, cents % 100);
    }

    static String percentage(long numeratorCents, long denominatorCents) {
        if (denominatorCents == 0L) {
            return "0.0";
        }
        return BigDecimal.valueOf(numeratorCents)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominatorCents), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static final class DailyTotals {
        private long incomeCents;
        private long expenseCents;
    }

    private static final class NamedTotal {
        private final long id;
        private final String name;
        private long amountCents;

        private NamedTotal(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
