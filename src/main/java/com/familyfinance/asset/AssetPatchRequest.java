package com.familyfinance.asset;

import tools.jackson.databind.JsonNode;
import java.time.LocalDate;

public record AssetPatchRequest(
        String name,
        JsonNode ownerMemberId,
        PropertyAssetRequest property,
        VehicleAssetRequest vehicle,
        AssetType type,
        LocalDate acquiredOn,
        String purchaseValue,
        String currentValue,
        Long createdBy,
        AssetStatus status) {
}
