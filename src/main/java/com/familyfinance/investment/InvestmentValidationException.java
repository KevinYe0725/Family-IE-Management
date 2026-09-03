package com.familyfinance.investment;

import java.util.Map;

public class InvestmentValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public InvestmentValidationException(Map<String, String> fields) {
        super("Investment validation failed");
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
