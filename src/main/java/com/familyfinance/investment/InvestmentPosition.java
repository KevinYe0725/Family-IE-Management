package com.familyfinance.investment;

import java.math.BigDecimal;

public record InvestmentPosition(
        BigDecimal quantity,
        long costCents,
        BigDecimal averageCostCents,
        long realizedProfitCents,
        long cashImpactCents,
        Long marketPriceCents,
        Long marketValueCents,
        Long unrealizedProfitCents) {
}
