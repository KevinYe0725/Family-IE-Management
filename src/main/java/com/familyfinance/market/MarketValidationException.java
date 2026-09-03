package com.familyfinance.market;

import java.util.Map;

public class MarketValidationException extends RuntimeException {
    private final Map<String, String> fields;
    MarketValidationException(Map<String, String> fields) { this.fields = Map.copyOf(fields); }
    public Map<String, String> fields() { return fields; }
}
