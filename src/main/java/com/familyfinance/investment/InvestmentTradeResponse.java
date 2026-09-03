package com.familyfinance.investment;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestmentTradeResponse(
        long id,
        long accountId,
        SecurityResponse security,
        InvestmentTradeType type,
        BigDecimal quantity,
        String price,
        String fee,
        String cashImpact,
        LocalDate tradedOn,
        long createdBy,
        InvestmentTradeSourceType sourceType,
        String sourceId) {

    static InvestmentTradeResponse from(InvestmentTrade trade, long cashImpactCents) {
        return new InvestmentTradeResponse(
                trade.getId(), trade.getAccount().getId(), SecurityResponse.from(trade.getSecurity()),
                trade.getType(), trade.getQuantity(), Money.formatCents(trade.getPriceCents()),
                Money.formatCents(trade.getFeeCents()), Money.formatCents(cashImpactCents), trade.getTradedOn(),
                trade.getCreatedBy().getId(), trade.getSourceType(), trade.getSourceId());
    }
}
