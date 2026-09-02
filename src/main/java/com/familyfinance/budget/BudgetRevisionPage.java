package com.familyfinance.budget;

import java.util.List;

public record BudgetRevisionPage(
        List<BudgetRevisionResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
