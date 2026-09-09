package com.familyfinance.budget;

import com.familyfinance.accounting.LedgerActivity;
import com.familyfinance.shared.Money;

public record BudgetUsageEntryResponse(
        Long entryId,
        String occurredOn,
        String amount,
        Long categoryId,
        String categoryName,
        Long memberId,
        String memberName,
        String note,
        String sourceType,
        Long sourceId) {

    static BudgetUsageEntryResponse from(LedgerActivity entry) {
        long categoryDimension = entry.getCategory().id();
        long memberDimension = entry.getMember().id();
        return new BudgetUsageEntryResponse(
                entry.getId(),
                entry.getOccurredOn().toString(),
                Money.formatCents(entry.getAmountCents()),
                categoryDimension < 1 ? null : categoryDimension,
                entry.getCategory().name(),
                memberDimension < 1 ? null : memberDimension,
                entry.getMember().name(),
                entry.getNote(),
                entry.sourceType(),
                entry.sourceId());
    }
}
