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
        Long createdByUserId,
        String createdByName,
        TransactionSourceType sourceType,
        Long categoryId,
        String categoryName,
        Long categoryParentId,
        int categoryLevel,
        String merchant,
        String location,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Long sourceId,
        String principalAmount,
        String interestAmount,String currency) {

    static TransactionResponse from(FinancialTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getKind(),
                Money.formatCents(transaction.getAmountCents()),
                transaction.getOccurredOn(),
                transaction.getAccount().getId(),
                transaction.getAccount().getName(),
                transaction.getMember() == null ? null : transaction.getMember().getId(),
                transaction.getMember() == null ? "全体（家庭共同）" : transaction.getMember().getName(),
                transaction.getCreatedByUser().getId(),
                transaction.getCreatedByUser().getDisplayName(),
                transaction.getSourceType(),
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
                transaction.getUpdatedAt(),
                transaction.getSourceId(),
                transaction.getLoanPrincipalCents()==null?null:Money.formatCents(transaction.getLoanPrincipalCents()),
                transaction.getLoanInterestCents()==null?null:Money.formatCents(transaction.getLoanInterestCents()),transaction.getAccount().getCurrency());
    }
}
