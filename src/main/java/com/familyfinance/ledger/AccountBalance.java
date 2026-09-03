package com.familyfinance.ledger;

/** A household-scoped, as-of-date balance for one active cash account. */
public record AccountBalance(long accountId, long balanceCents) {
}
