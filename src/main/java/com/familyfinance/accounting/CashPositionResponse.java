package com.familyfinance.accounting;

import java.time.LocalDate;
import java.util.List;

public record CashPositionResponse(
        LocalDate asOf,
        String currency,
        String availableCash,
        String knownAvailableCash,
        int uninitializedCount,
        List<Unconverted> unconverted) {
    public record Unconverted(long accountId, String currency, String nativeAmount) {}
}
