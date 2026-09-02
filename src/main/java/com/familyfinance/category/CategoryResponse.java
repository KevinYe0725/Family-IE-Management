package com.familyfinance.category;

import java.time.Instant;
import java.util.List;

public record CategoryResponse(
        Long id,
        TransactionKind kind,
        String name,
        String color,
        boolean defaultCategory,
        Instant createdAt,
        Long parentId,
        int level,
        List<CategoryResponse> children) {

    static CategoryResponse flat(Category category) {
        return from(category, List.of());
    }

    static CategoryResponse tree(Category category, List<CategoryResponse> children) {
        return from(category, children);
    }

    private static CategoryResponse from(Category category, List<CategoryResponse> children) {
        Category parent = category.getParent();
        return new CategoryResponse(
                category.getId(),
                category.getKind(),
                category.getName(),
                category.getColor(),
                category.isDefaultCategory(),
                category.getCreatedAt(),
                parent == null ? null : parent.getId(),
                parent == null ? 1 : 2,
                List.copyOf(children));
    }
}
