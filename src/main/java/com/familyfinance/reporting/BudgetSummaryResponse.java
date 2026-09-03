package com.familyfinance.reporting;

import com.familyfinance.shared.Money;

public record BudgetSummaryResponse(int activeBudgetCount, String planned, String spent, int nearLimitCount, int overLimitCount) {
    static BudgetSummaryResponse from(BudgetSummary value) {
        return new BudgetSummaryResponse(value.activeBudgetCount(), Money.formatCents(value.plannedCents()),
                Money.formatCents(value.spentCents()), value.nearLimitCount(), value.overLimitCount());
    }
}
