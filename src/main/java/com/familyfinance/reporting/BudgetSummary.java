package com.familyfinance.reporting;

public record BudgetSummary(int activeBudgetCount, long plannedCents, long spentCents, int nearLimitCount, int overLimitCount) {
}
