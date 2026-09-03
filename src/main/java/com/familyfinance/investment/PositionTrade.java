package com.familyfinance.investment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PositionTrade(
        long id,
        LocalDate tradedOn,
        InvestmentTradeType type,
        BigDecimal quantity,
        long priceCents,
        long feeCents) {
}
