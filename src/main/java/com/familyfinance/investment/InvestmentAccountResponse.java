package com.familyfinance.investment;

import java.time.Instant;

public record InvestmentAccountResponse(
        long id,
        String name,
        String brokerName,
        String currency,
        InvestmentAccountStatus status,
        long createdBy,
        Instant archivedAt) {

    static InvestmentAccountResponse from(InvestmentAccount account) {
        return new InvestmentAccountResponse(
                account.getId(), account.getName(), account.getBrokerName(), account.getCurrency(),
                account.isArchived() ? InvestmentAccountStatus.ARCHIVED : InvestmentAccountStatus.ACTIVE,
                account.getCreatedBy().getId(), account.getArchivedAt());
    }
}
