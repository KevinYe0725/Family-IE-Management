package com.familyfinance.budget;

import java.util.List;

public record BudgetTemplateCreateRequest(
        String name,
        List<RowRequest> rows) {

    public record RowRequest(
            BudgetScopeType scopeType,
            Long categoryId,
            Long memberId,
            String amount,
            String note) {
    }
}
