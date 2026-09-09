package com.familyfinance.budget;

import com.familyfinance.shared.Money;
import java.time.Instant;
import java.util.List;

public record BudgetTemplateResponse(
        Long id,
        String name,
        Instant createdAt,
        List<RowResponse> rows) {

    public record RowResponse(
            Long rowId,
            BudgetScopeType scopeType,
            Long categoryId,
            Long memberId,
            String amount,
            String note) {
        static RowResponse from(BudgetTemplateRow row) {
            return new RowResponse(row.getId(), row.getScopeType(), row.getCategoryId(), row.getMemberId(),
                    Money.formatCents(row.getAmountCents()), row.getNote());
        }
    }

    static BudgetTemplateResponse from(BudgetTemplate template, List<BudgetTemplateRow> rows) {
        return new BudgetTemplateResponse(template.getId(), template.getName(), template.getCreatedAt(),
                rows.stream().map(RowResponse::from).toList());
    }
}
