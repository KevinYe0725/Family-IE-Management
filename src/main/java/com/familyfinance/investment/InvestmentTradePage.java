package com.familyfinance.investment;

import java.util.List;

public record InvestmentTradePage(
        List<InvestmentTradeResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
