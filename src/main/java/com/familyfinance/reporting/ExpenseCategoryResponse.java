package com.familyfinance.reporting;

public record ExpenseCategoryResponse(Long categoryId, String categoryName, String amount, String sharePercent) {
}
