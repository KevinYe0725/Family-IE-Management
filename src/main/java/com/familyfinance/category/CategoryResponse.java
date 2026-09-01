package com.familyfinance.category;

import java.time.Instant;

public record CategoryResponse(
        Long id,
        TransactionKind kind,
        String name,
        String color,
        boolean defaultCategory,
        Instant createdAt) {

    static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getKind(),
                category.getName(),
                category.getColor(),
                category.isDefaultCategory(),
                category.getCreatedAt());
    }
}
