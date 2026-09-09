package com.familyfinance.transaction;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.fx.FxJournalRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionSummaryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final TransactionService transactionService;
    private final FxJournalRates fx;

    public TransactionSummaryService(TransactionService transactionService, FxJournalRates fx) {
        this.transactionService = transactionService;
        this.fx = fx;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransactionSummaryResponse summarize(long householdId, TransactionFilter filter) {
        List<FinancialTransaction> transactions = transactionService.findAllForCsvExport(householdId, filter);
        AmountAggregate income = new AmountAggregate();
        AmountAggregate expense = new AmountAggregate();
        Map<CategoryKey, AggregateRow> categories = new LinkedHashMap<>();
        Map<DailyKey, AggregateRow> daily = new LinkedHashMap<>();
        int unconvertedCount = 0;

        for (FinancialTransaction transaction : transactions) {
            BigDecimal amount = convertedAmount(householdId, transaction);
            if (amount == null) {
                unconvertedCount++;
            }
            AmountAggregate kindTotal = transaction.getKind() == TransactionKind.INCOME ? income : expense;
            kindTotal.add(amount);

            Category category = transaction.getCategory();
            CategoryKey categoryKey = new CategoryKey(category.getId(), transaction.getKind());
            categories.computeIfAbsent(categoryKey, ignored -> new AggregateRow(category.getName(), category.getColor()))
                    .add(amount);

            DailyKey dailyKey = new DailyKey(transaction.getOccurredOn(), transaction.getKind(), category.getId());
            daily.computeIfAbsent(dailyKey, ignored -> new AggregateRow())
                    .add(amount);
        }

        String balance = income.unknown || expense.unknown
                ? null
                : format(income.total.subtract(expense.total));
        return new TransactionSummaryResponse(
                "CNY",
                income.text(),
                expense.text(),
                balance,
                transactions.size(),
                unconvertedCount,
                categories.entrySet().stream()
                        .map(entry -> new TransactionSummaryResponse.CategorySummary(
                                entry.getKey().categoryId(),
                                entry.getValue().name,
                                entry.getValue().color,
                                entry.getKey().kind(),
                                entry.getValue().amount(),
                                entry.getValue().count))
                        .toList(),
                daily.entrySet().stream()
                        .sorted(Comparator.comparing((Map.Entry<DailyKey, AggregateRow> entry) -> entry.getKey().date())
                                .thenComparing(entry -> entry.getKey().kind().name())
                                .thenComparing(entry -> entry.getKey().categoryId()))
                        .map(entry -> new TransactionSummaryResponse.DailySummary(
                                entry.getKey().date(),
                                entry.getKey().kind(),
                                entry.getKey().categoryId(),
                                entry.getValue().amount(),
                                entry.getValue().count))
                        .toList());
    }

    private BigDecimal convertedAmount(long householdId, FinancialTransaction transaction) {
        Long amountCents = transaction.getAmountCents();
        String currency = transaction.getAccount().getCurrency();
        if (amountCents == null) {
            return null;
        }
        FxJournalRates.Rate rate = fx.sourceReference(
                householdId,
                "TRANSACTION",
                transaction.getId(),
                currency);
        if (rate == null || rate.value() == null) {
            return null;
        }
        return BigDecimal.valueOf(amountCents, 2)
                .multiply(rate.value())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String format(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record CategoryKey(Long categoryId, TransactionKind kind) {
    }

    private record DailyKey(LocalDate date, TransactionKind kind, Long categoryId) {
    }

    private static final class AmountAggregate {
        private BigDecimal total = ZERO;
        private boolean unknown;

        private void add(BigDecimal amount) {
            if (amount == null) {
                unknown = true;
            } else {
                total = total.add(amount);
            }
        }

        private String text() {
            return unknown ? null : format(total);
        }
    }

    private static final class AggregateRow {
        private BigDecimal total = ZERO;
        private boolean unknown;
        private int count;
        private String name;
        private String color;

        private AggregateRow() {
        }

        private AggregateRow(String name, String color) {
            this.name = name;
            this.color = color;
        }

        private void add(BigDecimal amount) {
            count++;
            if (amount == null) {
                unknown = true;
            } else {
                total = total.add(amount);
            }
        }

        private String amount() {
            return unknown ? null : format(total);
        }
    }
}
