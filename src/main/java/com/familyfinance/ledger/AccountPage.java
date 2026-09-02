package com.familyfinance.ledger;

import java.util.List;

public record AccountPage(
        List<AccountResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
