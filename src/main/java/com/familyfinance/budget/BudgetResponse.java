package com.familyfinance.budget;

import com.familyfinance.shared.Money;

public record BudgetResponse(
        Long id,
        String periodMonth,
        BudgetScopeType scopeType,
        Long categoryId,
        Long memberId,
        String amount,
        Integer version,
        boolean active) {

    static BudgetResponse from(Budget budget) {
        return new BudgetResponse(
                budget.getId(),
                budget.getPeriodMonth().toString(),
                budget.getScopeType(),
                budget.getCategory() == null ? null : budget.getCategory().getId(),
                budget.getMember() == null ? null : budget.getMember().getId(),
                Money.formatCents(budget.getAmountCents()),
                budget.getVersion(),
                budget.isActive());
    }
}
