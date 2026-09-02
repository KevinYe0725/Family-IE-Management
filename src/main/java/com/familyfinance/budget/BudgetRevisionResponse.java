package com.familyfinance.budget;

import com.familyfinance.shared.Money;
import java.time.Instant;

public record BudgetRevisionResponse(
        Long id,
        Long budgetId,
        String oldPeriodMonth,
        String newPeriodMonth,
        BudgetScopeType oldScopeType,
        BudgetScopeType newScopeType,
        Long oldCategoryId,
        Long newCategoryId,
        Long oldMemberId,
        Long newMemberId,
        String oldAmount,
        String newAmount,
        boolean oldActive,
        boolean newActive,
        Long changedByUserId,
        Instant changedAt) {

    static BudgetRevisionResponse from(BudgetRevision revision) {
        return new BudgetRevisionResponse(
                revision.getId(),
                revision.getBudget().getId(),
                revision.getOldPeriodMonth(),
                revision.getNewPeriodMonth(),
                revision.getOldScopeType(),
                revision.getNewScopeType(),
                revision.getOldCategoryId(),
                revision.getNewCategoryId(),
                revision.getOldMemberId(),
                revision.getNewMemberId(),
                Money.formatCents(revision.getOldAmountCents()),
                Money.formatCents(revision.getNewAmountCents()),
                revision.isOldActive(),
                revision.isNewActive(),
                revision.getChangedBy().getId(),
                revision.getChangedAt());
    }
}
