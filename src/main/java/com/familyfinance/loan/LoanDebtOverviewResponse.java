package com.familyfinance.loan;

import java.time.LocalDate;

/** Whole-household debt overview across the loans currently being repaid. */
public record LoanDebtOverviewResponse(
        int count,
        String remainingPrincipal,
        String remainingRepayment,
        String thirtyDayDue,
        String paidRepayment,
        String weightedAnnualRatePercent,
        LocalDate nextDueOn) {
}
