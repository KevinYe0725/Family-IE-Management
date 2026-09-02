package com.familyfinance.ledger.recurring;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

final class RecurrenceCalculator {
    private RecurrenceCalculator() {}

    static LocalDate firstDue(
            RecurringScheduleType type, Integer dayOfMonth, DayOfWeek dayOfWeek, LocalDate start) {
        if (type == RecurringScheduleType.MONTHLY) {
            YearMonth month = YearMonth.from(start);
            LocalDate candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            if (candidate.isBefore(start)) {
                month = month.plusMonths(1);
                candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            }
            return candidate;
        }
        int days = Math.floorMod(dayOfWeek.getValue() - start.getDayOfWeek().getValue(), 7);
        return start.plusDays(days);
    }

    static LocalDate nextDue(RecurringRule rule, LocalDate current) {
        if (rule.getScheduleType() == RecurringScheduleType.WEEKLY) {
            return current.plusWeeks(rule.getIntervalValue());
        }
        YearMonth target = YearMonth.from(current).plusMonths(rule.getIntervalValue());
        return target.atDay(Math.min(rule.getDayOfMonth(), target.lengthOfMonth()));
    }

    static LocalDate withinEnd(LocalDate due, LocalDate end) {
        return due == null || end == null || !due.isAfter(end) ? due : null;
    }
}
