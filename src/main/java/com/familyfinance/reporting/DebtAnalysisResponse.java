package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.util.List;

public record DebtAnalysisResponse(String liability, String asset, String debtRatioPercent,
                                   List<DebtProgressResponse> loans) {
    static DebtAnalysisResponse from(NetWorthResult value) {
        return new DebtAnalysisResponse(Money.formatCents(value.liabilityCents()), Money.formatCents(value.assetCents()),
                BigDecimal.valueOf(value.debtRatioTenths(), 1).toPlainString(),
                value.debtProgress().stream().map(DebtProgressResponse::from).toList());
    }
}
