package com.familyfinance.asset;

import java.math.BigDecimal;

public record PropertyAssetResponse(String address, BigDecimal areaSqm, String usageType) {

    static PropertyAssetResponse from(PropertyAsset property) {
        return new PropertyAssetResponse(property.getAddress(), property.getAreaSqm(), property.getUsageType());
    }
}
