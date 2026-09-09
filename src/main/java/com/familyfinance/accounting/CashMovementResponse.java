package com.familyfinance.accounting;

import java.time.LocalDate;
import java.util.List;

/** Kind is the cash leg direction. Internal transfers are not income or expense records. */
public record CashMovementResponse(
        String id,
        long journalId,
        String sourceType,
        long sourceId,
        LocalDate effectiveOn,
        long accountId,
        String accountName,
        String currency,
        String kind,
        String amount,
        boolean internalTransfer,
        String description) {
    public record Page(List<CashMovementResponse> items, int page, int size, long totalElements,
                       int totalPages, boolean hasNext) {}
}
