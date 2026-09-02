package com.familyfinance.asset;

import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record AssetResponse(
        long id,
        String name,
        AssetType type,
        Long ownerMemberId,
        LocalDate acquiredOn,
        String purchaseValue,
        String currentValue,
        AssetStatus status,
        long createdBy,
        Instant archivedAt,
        PropertyAssetResponse property,
        VehicleAssetResponse vehicle) {

    static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getName(),
                asset.getType(),
                asset.getOwnerMember() == null ? null : asset.getOwnerMember().getId(),
                asset.getAcquiredOn(),
                asset.getPurchaseValueCents() == null ? null : Money.formatCents(asset.getPurchaseValueCents()),
                Money.formatCents(asset.getCurrentValueCents()),
                asset.getStatus(),
                asset.getCreatedBy().getId(),
                asset.getArchivedAt(),
                asset.getProperty() == null ? null : PropertyAssetResponse.from(asset.getProperty()),
                asset.getVehicle() == null ? null : VehicleAssetResponse.from(asset.getVehicle()));
    }
}
