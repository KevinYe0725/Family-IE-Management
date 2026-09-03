package com.familyfinance.market;

import java.util.List;
import java.util.Set;

public interface MarketQuoteProvider {
    List<DailyQuote> fetchDaily(Set<String> symbols);

    default boolean available() {
        return true;
    }
}
