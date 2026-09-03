package com.familyfinance.reporting;

public record PortfolioTotalsResponse(
        String cost, String marketValue, String realizedProfit, String unrealizedProfit,
        String totalProfit, int unpricedPositions) {
}
