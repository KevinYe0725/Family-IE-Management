package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;
import java.math.BigDecimal;

record TransactionCriteria(
        long householdId,
        LocalDate from,
        LocalDate to,
        TransactionKind kind,
        Long memberId,
        Long categoryId,
        String keyword,
        BigDecimal minAmount, // 👈 2. 添加这两个参数
        BigDecimal maxAmount) {
}
