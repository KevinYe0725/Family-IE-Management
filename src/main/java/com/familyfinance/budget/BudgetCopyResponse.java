package com.familyfinance.budget;

public record BudgetCopyResponse(
        String fromMonth,
        String toMonth,
        int copied,
        int skipped) {
}
