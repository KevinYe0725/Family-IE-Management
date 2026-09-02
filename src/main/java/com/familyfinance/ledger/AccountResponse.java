package com.familyfinance.ledger;

import com.familyfinance.shared.Money;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        Instant archivedAt) {

    static AccountResponse from(FinancialAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                Money.formatCents(account.getOpeningBalanceCents()),
                account.getArchivedAt());
    }
}
