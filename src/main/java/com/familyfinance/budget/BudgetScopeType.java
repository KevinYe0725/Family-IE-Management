package com.familyfinance.budget;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum BudgetScopeType {
    TOTAL,
    CATEGORY,
    MEMBER;

    @JsonCreator
    public static BudgetScopeType fromJson(String value) {
        if (value == null) {
            throw new IllegalArgumentException("预算范围不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("预算范围只能是 TOTAL、CATEGORY 或 MEMBER");
        }
    }
}
