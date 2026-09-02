package com.familyfinance.ledger.recurring;

import com.familyfinance.category.TransactionKind;
import java.time.DayOfWeek;
import java.time.LocalDate;

public record RecurringRulePatchRequest(
        TransactionKind kind,
        String amount,
        RecurringScheduleType scheduleType,
        Integer intervalValue,
        Integer dayOfMonth,
        DayOfWeek dayOfWeek,
        LocalDate startOn,
        LocalDate endOn,
        Long accountId,
        Long memberId,
        Long categoryId,
        Long assignedUserId,
        Boolean paused) {}
