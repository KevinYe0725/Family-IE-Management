package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record MarketPriceResponse(
        long securityId, String tsCode, String name, String price, QuoteSource source,
        LocalDate tradeDate, Instant fetchedAt, boolean stale, String error) {
    static MarketPriceResponse noQuote(Security security, String error) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(), null,
                null, null, null, true, error);
    }
    static MarketPriceResponse manual(Security security, ManualPriceOverride override, boolean stale) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(),
                Money.formatCents(override.getPriceCents()), QuoteSource.MANUAL, override.getEffectiveOn(),
                null, stale, null);
    }
    static MarketPriceResponse tushare(Security security, MarketPriceSnapshot snapshot, boolean stale) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(),
                Money.formatCents(snapshot.getCloseCents()), QuoteSource.TUSHARE, snapshot.getTradeDate(),
                snapshot.getFetchedAt(), stale, null);
    }
}
