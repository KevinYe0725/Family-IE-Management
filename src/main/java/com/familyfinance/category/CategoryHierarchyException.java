package com.familyfinance.category;

import java.util.Map;

public class CategoryHierarchyException extends RuntimeException {

    private final Map<String, String> fields;

    public CategoryHierarchyException(String field, String message) {
        super(message);
        this.fields = Map.of(field, message);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
