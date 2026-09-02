package com.familyfinance.ledger;

public record AccountPatchRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance) {
}
