package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.util.List;

public record NetWorthResponse(String asset, String liability, String netWorth, List<AllocationResponse> allocation,
                               String debtRatioPercent, BudgetSummaryResponse budget,
                               InvestmentSummaryResponse investment, List<NetWorthSnapshotResponse> history) {
    static NetWorthResponse from(NetWorthResult value, List<NetWorthSnapshot> history) {
        return new NetWorthResponse(Money.formatCents(value.assetCents()), Money.formatCents(value.liabilityCents()),
                Money.formatCents(value.netWorthCents()), value.allocation().stream().map(AllocationResponse::from).toList(),
                BigDecimal.valueOf(value.debtRatioTenths(), 1).toPlainString(), BudgetSummaryResponse.from(value.budget()),
                InvestmentSummaryResponse.from(value.investment()), history.stream().map(NetWorthSnapshotResponse::from).toList());
    }
}
