package com.familyfinance.ledger;

public record AccountCreateRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance) {
}
