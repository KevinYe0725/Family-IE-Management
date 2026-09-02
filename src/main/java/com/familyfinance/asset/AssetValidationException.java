package com.familyfinance.asset;

import java.util.Map;

public class AssetValidationException extends RuntimeException {

    private final Map<String, String> fields;

    AssetValidationException(Map<String, String> fields) {
        super("Asset validation failed");
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
