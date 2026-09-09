package com.familyfinance.budget;

import java.util.List;

public record BudgetUsageEntryPage(
        List<BudgetUsageEntryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
