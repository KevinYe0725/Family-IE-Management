package com.familyfinance.budget;

public record BudgetCreateRequest(
        String periodMonth,
        BudgetScopeType scopeType,
        Long categoryId,
        Long memberId,
        String amount) {
}
