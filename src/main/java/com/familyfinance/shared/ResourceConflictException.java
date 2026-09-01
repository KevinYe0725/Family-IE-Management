package com.familyfinance.shared;

public class ResourceConflictException extends RuntimeException {

    private final String code;

    public ResourceConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
