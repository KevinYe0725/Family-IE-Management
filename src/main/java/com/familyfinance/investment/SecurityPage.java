package com.familyfinance.investment;

import java.util.List;

public record SecurityPage(
        List<SecurityResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
