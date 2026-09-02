package com.familyfinance.asset;

import java.util.List;

public record AssetPage(
        List<AssetResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
