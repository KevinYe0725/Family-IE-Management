package com.familyfinance.transaction;

public record TransactionFilter(
        String month,
        String from,
        String to,
        String kind,
        Long accountId,
        Long memberId,
        Long categoryId,
        String q,
        Long bankAccountId,
        String sourceType) {

    public TransactionFilter(
            String month,
            String from,
            String to,
            String kind,
            Long accountId,
            Long memberId,
            Long categoryId,
            String q) {
        this(month, from, to, kind, accountId, memberId, categoryId, q, null, null);
    }
    public TransactionFilter(
            String month,
            String from,
            String to,
            String kind,
            Long accountId,
            Long memberId,
            Long categoryId,
            String q,
            Long bankAccountId) {
        this(month, from, to, kind, accountId, memberId, categoryId, q, bankAccountId, null);
    }
}
