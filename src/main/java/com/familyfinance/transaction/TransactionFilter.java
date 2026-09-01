package com.familyfinance.transaction;

public record TransactionFilter(
        String month,
        String from,
        String to,
        String kind,
        Long memberId,
        Long categoryId,
        String q) {
}
