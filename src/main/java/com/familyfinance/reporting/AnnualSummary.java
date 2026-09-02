package com.familyfinance.reporting;

public record AnnualSummary(
        String totalIncome,
        String totalExpense,
        String totalBalance,
        String monthlyAverageIncome,
        String monthlyAverageExpense,
        String monthlyAverageBalance) {
}
