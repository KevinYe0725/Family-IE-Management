package com.familyfinance.asset;

import java.time.LocalDate;

public record AssetCreateRequest(
        String name,
        AssetType type,
        Long ownerMemberId,
        LocalDate acquiredOn,
        String purchaseValue,
        String currentValue,
        PropertyAssetRequest property,
        VehicleAssetRequest vehicle) {
}
