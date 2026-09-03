package com.familyfinance.market;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyQuote(
        String symbol, LocalDate tradeDate, long openCents, long highCents, long lowCents,
        long closeCents, long preCloseCents, BigDecimal pctChange) {
}
