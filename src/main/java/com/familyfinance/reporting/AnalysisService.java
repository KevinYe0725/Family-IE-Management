package com.familyfinance.reporting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnalysisService {

    private static final Sort MONTH_SORT = Sort.by(
            Sort.Order.asc("occurredOn"),
            Sort.Order.asc("id"));
    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("occurredOn"),
            Sort.Order.desc("id"));

    private final FinancialTransactionRepository transactionRepository;

    public AnalysisService(FinancialTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public AnalysisResponse analysis(long householdId, YearMonth month) {
        List<FinancialTransaction> currentTransactions = transactionRepository.findByHouseholdIdAndOccurredOnBetween(
                householdId,
                month.atDay(1),
                month.atEndOfMonth(),
                MONTH_SORT);
        List<FinancialTransaction> historyTransactions = transactionRepository.findByHouseholdIdAndKindAndOccurredOnBefore(
                householdId,
                TransactionKind.EXPENSE,
                month.atDay(1),
                HISTORY_SORT);

        long currentExpenseCents = expenseTotal(currentTransactions);
        Map<YearMonth, Long> historyByMonth = historicalExpenseTotals(historyTransactions);
        boolean historySufficient = historyByMonth.size() >= 2;
        String historyStatus = historySufficient ? "sufficient" : "insufficient";
        List<InsightResponse> insights = new ArrayList<>();

        if (currentExpenseCents == 0L) {
            return new AnalysisResponse(historyStatus, List.of());
        }

        if (historySufficient) {
            insights.add(monthlyComparison(currentExpenseCents, historyByMonth));
        }
        topCategory(currentTransactions, currentExpenseCents).ifPresent(insights::add);
        largestExpense(currentTransactions).ifPresent(insights::add);

        return new AnalysisResponse(historyStatus, insights.stream().limit(3).toList());
    }

    private static long expenseTotal(List<FinancialTransaction> transactions) {
        long totalCents = 0L;
        for (FinancialTransaction transaction : transactions) {
            if (transaction.getKind() == TransactionKind.EXPENSE) {
                totalCents += transaction.getAmountCents();
            }
        }
        return totalCents;
    }

    private static Map<YearMonth, Long> historicalExpenseTotals(List<FinancialTransaction> transactions) {
        Map<YearMonth, Long> totals = new LinkedHashMap<>();
        for (FinancialTransaction transaction : transactions) {
            if (transaction.getKind() == TransactionKind.EXPENSE) {
                YearMonth month = YearMonth.from(transaction.getOccurredOn());
                if (!totals.containsKey(month) && totals.size() == 3) {
                    break;
                }
                totals.merge(month, transaction.getAmountCents(), Long::sum);
            }
        }
        totals.entrySet().removeIf(entry -> entry.getValue() == 0L);
        return totals;
    }

    private static InsightResponse monthlyComparison(long currentExpenseCents, Map<YearMonth, Long> historyByMonth) {
        long historyTotalCents = 0L;
        for (Long monthlyTotal : historyByMonth.values()) {
            historyTotalCents += monthlyTotal;
        }
        BigDecimal average = BigDecimal.valueOf(historyTotalCents)
                .divide(BigDecimal.valueOf(historyByMonth.size()), 4, RoundingMode.HALF_UP);
        BigDecimal percent = BigDecimal.valueOf(currentExpenseCents)
                .subtract(average)
                .multiply(BigDecimal.valueOf(100))
                .divide(average, 1, RoundingMode.HALF_UP);
        String metric = percent.toPlainString() + "%";
        String type = monthlyComparisonType(percent);
        String title = monthlyComparisonTitle(percent);
        String message = "本月支出 " + DashboardService.formatCents(currentExpenseCents)
                + "，前三个月有数据月份平均 "
                + DashboardService.formatCents(average.setScale(0, RoundingMode.HALF_UP).longValueExact())
                + "，变化 " + metric + "。";
        return new InsightResponse(type, title, message, metric);
    }

    private static String monthlyComparisonType(BigDecimal percent) {
        if (percent.signum() > 0) {
            return "MONTHLY_INCREASE";
        }
        if (percent.signum() < 0) {
            return "MONTHLY_DECREASE";
        }
        return "MONTHLY_STABLE";
    }

    private static String monthlyComparisonTitle(BigDecimal percent) {
        if (percent.signum() > 0) {
            return "本月支出高于近期平均";
        }
        if (percent.signum() < 0) {
            return "本月支出低于近期平均";
        }
        return "本月支出与近期平均持平";
    }

    private static java.util.Optional<InsightResponse> topCategory(
            List<FinancialTransaction> transactions,
            long currentExpenseCents) {
        Map<Long, CategoryTotal> totals = new LinkedHashMap<>();
        for (FinancialTransaction transaction : transactions) {
            if (transaction.getKind() == TransactionKind.EXPENSE) {
                totals.computeIfAbsent(
                                transaction.getCategory().getId(),
                                ignored -> new CategoryTotal(
                                        transaction.getCategory().getId(),
                                        transaction.getCategory().getName()))
                        .amountCents += transaction.getAmountCents();
            }
        }
        return totals.values().stream()
                .sorted(Comparator.comparingLong((CategoryTotal total) -> total.amountCents)
                        .reversed()
                        .thenComparingLong(total -> total.id))
                .findFirst()
                .map(total -> {
                    String share = DashboardService.percentage(total.amountCents, currentExpenseCents);
                    return new InsightResponse(
                            "TOP_CATEGORY",
                            "最高支出分类",
                            total.name + " 是本月最高支出分类，占本月支出的 " + share + "%。",
                            share + "%");
                });
    }

    private static java.util.Optional<InsightResponse> largestExpense(List<FinancialTransaction> transactions) {
        return transactions.stream()
                .filter(transaction -> transaction.getKind() == TransactionKind.EXPENSE)
                .sorted(Comparator.comparingLong(FinancialTransaction::getAmountCents)
                        .reversed()
                        .thenComparing(FinancialTransaction::getOccurredOn)
                        .thenComparingLong(FinancialTransaction::getId))
                .findFirst()
                .map(transaction -> new InsightResponse(
                        "LARGEST_EXPENSE",
                        "最大单笔支出",
                        largestExpenseMessage(transaction),
                        DashboardService.formatCents(transaction.getAmountCents())));
    }

    private static String largestExpenseMessage(FinancialTransaction transaction) {
        String label = transaction.getNote() == null ? transaction.getCategory().getName() : transaction.getNote();
        return transaction.getOccurredOn() + " 的 " + label + " 是本月最大单笔支出，金额 "
                + DashboardService.formatCents(transaction.getAmountCents()) + "。";
    }

    private static final class CategoryTotal {
        private final long id;
        private final String name;
        private long amountCents;

        private CategoryTotal(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
