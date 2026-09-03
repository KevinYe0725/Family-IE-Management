package com.familyfinance.reporting;

public record InvestmentSummary(long marketValueCents, int positionCount, int unpricedPositionCount,
                                boolean manualPrice, boolean stalePrice, boolean missingPrice) {
}
