package com.familyfinance.loan;

import java.time.LocalDate;

public record InstallmentDraft(int installmentNo, LocalDate dueOn, long principalCents, long interestCents,
                               long remainingPrincipalCents) {
}
