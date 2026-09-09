package com.familyfinance.budget;

public record BudgetTemplateApplyResponse(
        String periodMonth,
        int copied,
        int skipped) {
}
