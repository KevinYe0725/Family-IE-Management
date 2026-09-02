package com.familyfinance.reporting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnnualStatsService {

    private static final Sort YEAR_SORT = Sort.by(
            Sort.Order.asc("occurredOn"),
            Sort.Order.asc("id"));

    private final FinancialTransactionRepository transactionRepository;

    public AnnualStatsService(FinancialTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public AnnualStatsResponse annualStats(long householdId, int year) {
        List<FinancialTransaction> transactions = transactionRepository.findByHouseholdIdAndOccurredOnBetween(
                householdId,
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31),
                YEAR_SORT);

        Map<Integer, long[]> monthlyIncomeExpense = new TreeMap<>();
        for (int month = 1; month <= 12; month++) {
            monthlyIncomeExpense.put(month, new long[]{0L, 0L});
        }

        long totalIncomeCents = 0L;
        long totalExpenseCents = 0L;

        for (FinancialTransaction transaction : transactions) {
            int month = transaction.getOccurredOn().getMonthValue();
            long[] incomeExpense = monthlyIncomeExpense.get(month);
            if (transaction.getKind() == TransactionKind.INCOME) {
                incomeExpense[0] += transaction.getAmountCents();
                totalIncomeCents += transaction.getAmountCents();
            } else {
                incomeExpense[1] += transaction.getAmountCents();
                totalExpenseCents += transaction.getAmountCents();
            }
        }

        long totalBalanceCents = totalIncomeCents - totalExpenseCents;
        long monthlyAverageIncomeCents = totalIncomeCents / 12;
        long monthlyAverageExpenseCents = totalExpenseCents / 12;
        long monthlyAverageBalanceCents = totalBalanceCents / 12;

        List<MonthlyCashFlow> monthlyCashFlows = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            long[] incomeExpense = monthlyIncomeExpense.get(month);
            long incomeCents = incomeExpense[0];
            long expenseCents = incomeExpense[1];
            long balanceCents = incomeCents - expenseCents;
            String vsAveragePercent = calculateVsAveragePercent(expenseCents, monthlyAverageExpenseCents);
            monthlyCashFlows.add(new MonthlyCashFlow(
                    month,
                    DashboardService.formatCents(incomeCents),
                    DashboardService.formatCents(expenseCents),
                    DashboardService.formatCents(balanceCents),
                    vsAveragePercent));
        }

        AnnualSummary summary = new AnnualSummary(
                DashboardService.formatCents(totalIncomeCents),
                DashboardService.formatCents(totalExpenseCents),
                DashboardService.formatCents(totalBalanceCents),
                DashboardService.formatCents(monthlyAverageIncomeCents),
                DashboardService.formatCents(monthlyAverageExpenseCents),
                DashboardService.formatCents(monthlyAverageBalanceCents));

        return new AnnualStatsResponse(year, summary, monthlyCashFlows);
    }

    private static String calculateVsAveragePercent(long monthlyExpenseCents, long averageExpenseCents) {
        if (averageExpenseCents == 0L) {
            return "0.0";
        }
        return BigDecimal.valueOf(monthlyExpenseCents)
                .subtract(BigDecimal.valueOf(averageExpenseCents))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(averageExpenseCents), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
