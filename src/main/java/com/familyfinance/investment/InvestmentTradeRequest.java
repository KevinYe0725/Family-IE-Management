package com.familyfinance.investment;

import java.time.LocalDate;

public record InvestmentTradeRequest(
        Long accountId,
        Long securityId,
        String tsCode,
        String securityName,
        InvestmentTradeType type,
        String quantity,
        String price,
        String fee,
        LocalDate tradedOn,
        Long createdBy,
        InvestmentTradeSourceType sourceType,
        String sourceId) {
}
