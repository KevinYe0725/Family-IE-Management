package com.familyfinance.reporting;

import com.familyfinance.market.QuoteSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PortfolioPositionResponse(
        long accountId, String accountName, String brokerName,
        long securityId, String tsCode, String name,
        BigDecimal quantity, String averageCost, String cost, String price, String marketValue,
        String realizedProfit, String unrealizedProfit, String totalProfit, String allocationPercent,
        QuoteSource source, LocalDate tradeDate, Instant fetchedAt, boolean stale, String error) {
}
