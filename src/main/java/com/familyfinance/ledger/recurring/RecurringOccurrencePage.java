package com.familyfinance.ledger.recurring;

import java.util.List;

public record RecurringOccurrencePage(
        List<RecurringOccurrenceResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {}
