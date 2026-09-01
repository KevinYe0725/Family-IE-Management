package com.familyfinance.reporting;

import java.util.List;

public record DashboardResponse(
        DashboardSummaryResponse summary,
        List<DailyTrendResponse> daily,
        List<ExpenseCategoryResponse> expenseByCategory,
        List<MemberExpenseResponse> expenseByMember) {
}
