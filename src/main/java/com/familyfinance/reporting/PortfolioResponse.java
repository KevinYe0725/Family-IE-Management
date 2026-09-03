package com.familyfinance.reporting;

import java.util.List;

public record PortfolioResponse(List<PortfolioPositionResponse> positions, PortfolioTotalsResponse totals) {
}
