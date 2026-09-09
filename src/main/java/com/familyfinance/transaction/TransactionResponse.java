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
        String interestAmount,
        String currency,
        Boolean cashImpact,
        Long settlementAssetId) {

    public TransactionResponse {
        cashImpact = !Boolean.FALSE.equals(cashImpact);
    }

    public TransactionResponse(
            Long id, TransactionKind kind, String amount, LocalDate occurredOn,
            Long accountId, String accountName, Long memberId, String memberName,
            Long createdByUserId, String createdByName, TransactionSourceType sourceType,
            Long categoryId, String categoryName, Long categoryParentId, int categoryLevel,
            String merchant, String location, String note, Instant createdAt, Instant updatedAt,
            Long sourceId, String principalAmount, String interestAmount, String currency) {
        this(id, kind, amount, occurredOn, accountId, accountName, memberId, memberName,
                createdByUserId, createdByName, sourceType, categoryId, categoryName, categoryParentId,
                categoryLevel, merchant, location, note, createdAt, updatedAt, sourceId,
                principalAmount, interestAmount, currency, true, null);
    }

    static TransactionResponse from(FinancialTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getKind(),
                Money.formatCents(transaction.getAmountCents()),
                transaction.getOccurredOn(),
                transaction.getAccount().getId(),
                transaction.hasCashImpact() ? transaction.getAccount().getName() : "买方代偿（非现金）",
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
                transaction.getLoanInterestCents()==null?null:Money.formatCents(transaction.getLoanInterestCents()),
                transaction.getAccount().getCurrency(),
                transaction.hasCashImpact(),
                transaction.getAssetSettlementId());
    }
}
