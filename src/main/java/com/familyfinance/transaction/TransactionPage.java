package com.familyfinance.transaction;

import java.util.List;

record TransactionPage(
        List<TransactionResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
