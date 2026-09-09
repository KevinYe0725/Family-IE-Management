package com.familyfinance.budget;

import java.math.BigDecimal;

/** One active budget row a prospective expense would count toward, with post-entry state. */
public record BudgetHitResponse(
        Long budgetId,
        BudgetScopeType scopeType,
        Long categoryId,
        String categoryName,
        Long memberId,
        String memberName,
        String amount,
        String spent,
        String spentAfter,
        BigDecimal percentAfter,
        BudgetUsageStatus statusAfter) {
}
