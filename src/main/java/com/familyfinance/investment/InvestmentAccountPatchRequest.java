package com.familyfinance.investment;

import java.time.Instant;

public record InvestmentAccountPatchRequest(
        String name,
        String brokerName,
        String currency,
        Long createdBy,
        Instant archivedAt) {
}
