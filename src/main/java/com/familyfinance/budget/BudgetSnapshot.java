package com.familyfinance.budget;

record BudgetSnapshot(
        String periodMonth,
        BudgetScopeType scopeType,
        Long categoryId,
        Long memberId,
        Long amountCents,
        boolean active) {

    static BudgetSnapshot from(Budget budget) {
        return new BudgetSnapshot(
                budget.getPeriodMonth().toString(),
                budget.getScopeType(),
                budget.getCategory() == null ? null : budget.getCategory().getId(),
                budget.getMember() == null ? null : budget.getMember().getId(),
                budget.getAmountCents(),
                budget.isActive());
    }
}
