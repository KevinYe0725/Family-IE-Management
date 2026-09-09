package com.familyfinance.budget;

public record BudgetTotalRequest(
        String periodMonth,
        String amount,
        Integer version) {
}
