package com.familyfinance.reporting;

public record DebtProgress(long loanId, String loanName, long originalPrincipalCents,
                           long currentPrincipalCents, int repaidShareTenths) {
}
