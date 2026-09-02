package com.familyfinance.ledger.recurring;

import java.time.LocalDate;

public record RecurringOccurrenceResponse(
        Long id,
        Long ruleId,
        LocalDate dueOn,
        RecurringOccurrenceStatus status,
        Long assignedUserId,
        Long confirmedTransactionId) {
    static RecurringOccurrenceResponse from(RecurringOccurrence occurrence) {
        return new RecurringOccurrenceResponse(
                occurrence.getId(), occurrence.getRule().getId(), occurrence.getDueOn(), occurrence.getStatus(),
                occurrence.getAssignedUser() == null ? null : occurrence.getAssignedUser().getId(),
                occurrence.getConfirmedTransaction() == null ? null : occurrence.getConfirmedTransaction().getId());
    }
}
