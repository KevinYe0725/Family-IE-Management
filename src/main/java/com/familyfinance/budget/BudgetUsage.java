package com.familyfinance.budget;

import java.math.BigDecimal;

public record BudgetUsage(
        BudgetResponse budget,
        long spentCents,
        long remainingCents,
        BigDecimal percent,
        BudgetUsageStatus status,
        boolean rollupCategories) {
}
