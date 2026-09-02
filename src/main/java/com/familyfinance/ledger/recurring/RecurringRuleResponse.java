package com.familyfinance.ledger.recurring;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.Money;
import java.time.DayOfWeek;
import java.time.LocalDate;

public record RecurringRuleResponse(
        Long id,
        TransactionKind kind,
        String amount,
        RecurringScheduleType scheduleType,
        Integer intervalValue,
        Integer dayOfMonth,
        DayOfWeek dayOfWeek,
        LocalDate startOn,
        LocalDate endOn,
        LocalDate nextDueOn,
        Long accountId,
        String accountName,
        Long memberId,
        String memberName,
        Long categoryId,
        String categoryName,
        Long assignedUserId,
        String assignedUserName,
        boolean active,
        boolean paused,
        Long createdByUserId) {
    static RecurringRuleResponse from(RecurringRule rule) {
        return new RecurringRuleResponse(
                rule.getId(), rule.getKind(), Money.formatCents(rule.getAmountCents()),
                rule.getScheduleType(), rule.getIntervalValue(), rule.getDayOfMonth(), rule.getDayOfWeek(),
                rule.getStartOn(), rule.getEndOn(), rule.getNextDueOn(),
                rule.getAccount().getId(), rule.getAccount().getName(),
                rule.getMember().getId(), rule.getMember().getName(),
                rule.getCategory().getId(), rule.getCategory().getName(),
                rule.getAssignedUser().getId(), rule.getAssignedUser().getDisplayName(),
                rule.isActive(), rule.isPaused(), rule.getCreatedBy().getId());
    }
}
