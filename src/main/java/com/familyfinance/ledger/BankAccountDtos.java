package com.familyfinance.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Wire contracts for a bank identity and its native-currency cash children. */
public final class BankAccountDtos {

    private BankAccountDtos() {
    }

    public record BankAccount(
            Long id,
            String name,
            String bankName,
            String cardLastFour,
            Instant archivedAt,
            List<AccountResponse> accounts) {
    }

    public record CreateRequest(
            String name,
            String bankName,
            String cardLastFour,
            List<BalanceRequest> balances) {
    }

    public record PatchRequest(
            String name,
            String bankName,
            String cardLastFour) {
    }

    public record BalanceRequest(
            String currency,
            String openingBalance,
            String openingOn) {
    }

    public record BalanceInput(
            String currency,
            String openingBalance,
            LocalDate openingOn) {
    }
}
