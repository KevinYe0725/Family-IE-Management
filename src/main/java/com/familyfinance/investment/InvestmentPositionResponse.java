package com.familyfinance.investment;

import com.familyfinance.shared.Money;
import java.math.RoundingMode;

public record InvestmentPositionResponse(
        long accountId,
        SecurityResponse security,
        java.math.BigDecimal quantity,
        String cost,
        String averageCost,
        String realizedProfit,
        String cashImpact,
        String marketPrice,
        String marketValue,
        String unrealizedProfit) {

    static InvestmentPositionResponse from(long accountId, Security security, InvestmentPosition position) {
        return new InvestmentPositionResponse(
                accountId,
                SecurityResponse.from(security),
                position.quantity(),
                Money.formatCents(position.costCents()),
                position.averageCostCents().movePointLeft(2).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                Money.formatCents(position.realizedProfitCents()),
                Money.formatCents(position.cashImpactCents()),
                position.marketPriceCents() == null ? null : Money.formatCents(position.marketPriceCents()),
                position.marketValueCents() == null ? null : Money.formatCents(position.marketValueCents()),
                position.unrealizedProfitCents() == null ? null : Money.formatCents(position.unrealizedProfitCents()));
    }
}
