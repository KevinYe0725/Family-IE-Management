package com.familyfinance.reporting;

import java.util.List;

public record NetWorthResult(long assetCents, long liabilityCents, long netWorthCents,
                             List<AllocationSlice> allocation, int debtRatioTenths,
                             List<DebtProgress> debtProgress, BudgetSummary budget,
                             InvestmentSummary investment) {
}
