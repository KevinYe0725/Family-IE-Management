package com.familyfinance.market;

public class MarketProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    MarketProviderException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
