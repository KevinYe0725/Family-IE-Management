package com.familyfinance.budget;

public record BudgetPatchRequest(
        Integer version,
        String periodMonth,
        BudgetScopeType scopeType,
        Long categoryId,
        Long memberId,
        String amount,
        Boolean active) {
}
