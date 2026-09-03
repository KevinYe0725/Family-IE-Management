package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;

public record DebtProgressResponse(long loanId, String loanName, String originalPrincipal,
                                   String currentPrincipal, String repaidPercent) {
    static DebtProgressResponse from(DebtProgress value) {
        return new DebtProgressResponse(value.loanId(), value.loanName(), Money.formatCents(value.originalPrincipalCents()),
                Money.formatCents(value.currentPrincipalCents()), BigDecimal.valueOf(value.repaidShareTenths(), 1).toPlainString());
    }
}
