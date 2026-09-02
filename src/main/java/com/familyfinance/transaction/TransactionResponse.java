package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
        Long id,
        TransactionKind kind,
        String amount,
        LocalDate occurredOn,
        Long accountId,
        String accountName,
        Long memberId,
        String memberName,
        Long categoryId,
        String categoryName,
        Long categoryParentId,
        int categoryLevel,
        String merchant,
        String location,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    static TransactionResponse from(FinancialTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getKind(),
                Money.formatCents(transaction.getAmountCents()),
                transaction.getOccurredOn(),
                transaction.getAccount().getId(),
                transaction.getAccount().getName(),
                transaction.getMember().getId(),
                transaction.getMember().getName(),
                transaction.getCategory().getId(),
                transaction.getCategory().getName(),
                transaction.getCategory().getParent() == null
                        ? null
                        : transaction.getCategory().getParent().getId(),
                transaction.getCategory().getParent() == null ? 1 : 2,
                transaction.getMerchant(),
                transaction.getLocation(),
                transaction.getNote(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt());
    }
}
