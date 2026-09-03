package com.familyfinance.investment;

public record InvestmentTradeMutationResponse(
        InvestmentTradeResponse trade,
        InvestmentPositionResponse position) {
}
