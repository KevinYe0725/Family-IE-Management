package com.familyfinance.ledger;

import com.familyfinance.shared.Money;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        Instant archivedAt,
        boolean openingConfirmed,
        java.time.LocalDate openingOn,
        String balance,
        String availableBalance,
        WalletProvider walletProvider,
        String bankName,
        String cardLastFour,
        Long bankAccountId,
        String bankAccountName) {

    public AccountResponse(
            Long id,
            String name,
            AccountType type,
            String currency,
            String openingBalance,
            Instant archivedAt,
            boolean openingConfirmed,
            java.time.LocalDate openingOn,
            String balance,
            String availableBalance,
            WalletProvider walletProvider,
            String bankName,
            String cardLastFour) {
        this(id, name, type, currency, openingBalance, archivedAt, openingConfirmed, openingOn,
                balance, availableBalance, walletProvider, bankName, cardLastFour, null, null);
    }

    static AccountResponse from(FinancialAccount account, long balance) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                Money.formatCents(account.getOpeningBalanceCents()),
                account.getArchivedAt(),
                account.isOpeningConfirmed(),
                account.getOpeningOn(),
                account.isOpeningConfirmed()?Money.formatCents(balance):null,
                account.isOpeningConfirmed()?Money.formatCents(balance):null,
                account.getWalletProvider(), account.getBankName(), account.getCardLastFour(),
                account.getBankAccountId(), account.getBankAccountName());
    }
}
