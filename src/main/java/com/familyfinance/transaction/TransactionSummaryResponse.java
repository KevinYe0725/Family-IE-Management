package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import java.util.List;

public record TransactionSummaryResponse(
        String currency,
        String income,
        String expense,
        String balance,
        int transactionCount,
        int unconvertedCount,
        List<CategorySummary> categories,
        List<DailySummary> daily) {

    public record CategorySummary(
            Long categoryId,
            String name,
            String color,
            TransactionKind kind,
            String amount,
            int count) {
    }

    public record DailySummary(
            java.time.LocalDate date,
            TransactionKind kind,
            Long categoryId,
            String amount,
            int count) {
    }
}
