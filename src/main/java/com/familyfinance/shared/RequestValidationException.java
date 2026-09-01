package com.familyfinance.shared;

import java.util.Map;

public class RequestValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public RequestValidationException(Map<String, String> fields) {
        super("Validation failed");
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
