package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;

record TransactionCriteria(
        long householdId,
        LocalDate from,
        LocalDate to,
        TransactionKind kind,
        Long accountId,
        Long memberId,
        Long categoryId,
        String keyword) {
}
