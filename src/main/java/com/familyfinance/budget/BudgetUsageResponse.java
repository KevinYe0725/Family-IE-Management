package com.familyfinance.budget;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;

public record BudgetUsageResponse(
        BudgetResponse budget,
        String spent,
        String remaining,
        BigDecimal percent,
        BudgetUsageStatus status,
        boolean rollupCategories) {

    static BudgetUsageResponse from(BudgetUsage usage) {
        return new BudgetUsageResponse(
                usage.budget(),
                Money.formatCents(usage.spentCents()),
                Money.formatCents(usage.remainingCents()),
                usage.percent(),
                usage.status(),
                usage.rollupCategories());
    }
}
