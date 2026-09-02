package com.familyfinance.ledger.recurring;

import java.util.List;

public record RecurringRulePage(
        List<RecurringRuleResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {}
