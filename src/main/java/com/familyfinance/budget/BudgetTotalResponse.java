package com.familyfinance.budget;

import com.familyfinance.shared.Money;

/** Amount is null while the household has not set a total budget for the month. */
public record BudgetTotalResponse(
        String periodMonth,
        String amount,
        Integer version) {

    public static BudgetTotalResponse absent(String periodMonth) {
        return new BudgetTotalResponse(periodMonth, null, 0);
    }

    static BudgetTotalResponse from(BudgetMonthTotal total) {
        return new BudgetTotalResponse(
                total.getPeriodMonth(),
                Money.formatCents(total.getAmountCents()),
                total.getVersion());
    }
}
