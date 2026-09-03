package com.familyfinance.investment;

import java.util.List;

public record InvestmentAccountPage(
        List<InvestmentAccountResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
