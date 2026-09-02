package com.familyfinance.category;

import java.util.List;

public record CategoryPage(
        List<CategoryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
