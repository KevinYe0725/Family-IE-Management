package com.familyfinance.category;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum TransactionKind {
    INCOME,
    EXPENSE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TransactionKind fromJson(String value) {
        if (value == null) {
            throw new IllegalArgumentException("收支类型不能为空");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "income" -> INCOME;
            case "expense" -> EXPENSE;
            default -> throw new IllegalArgumentException("收支类型只能是 income 或 expense");
        };
    }
}
