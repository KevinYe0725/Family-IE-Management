package com.familyfinance.reporting;

public record MonthlyCashFlow(
        int month,
        String income,
        String expense,
        String balance,
        String vsAveragePercent) {
}
