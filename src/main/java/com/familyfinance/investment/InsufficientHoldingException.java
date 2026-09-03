package com.familyfinance.investment;

public class InsufficientHoldingException extends RuntimeException {

    public InsufficientHoldingException() {
        super("卖出数量超过可用持仓");
    }
}
