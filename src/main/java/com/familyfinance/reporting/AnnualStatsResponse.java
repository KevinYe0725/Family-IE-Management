package com.familyfinance.reporting;

public record AnnualStatsResponse(
        int year,
        AnnualSummary summary,
        java.util.List<MonthlyCashFlow> monthlyCashFlows) {
}
