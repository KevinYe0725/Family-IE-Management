package com.familyfinance.asset;

import java.util.List;

public record AssetValuationPage(
        List<AssetValuationResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {
}
