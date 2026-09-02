package com.familyfinance.transaction;

import java.math.BigDecimal;

public record TransactionFilter(
        String month,
        String from,
        String to,
        String kind,
        Long memberId,
        Long categoryId,
        String q,
        BigDecimal minAmount,
        BigDecimal maxAmount) {
}
