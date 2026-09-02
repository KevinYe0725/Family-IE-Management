package com.familyfinance.asset;

public record VehicleAssetResponse(String brandModel, String plateHint, Integer purchaseYear) {

    static VehicleAssetResponse from(VehicleAsset vehicle) {
        return new VehicleAssetResponse(vehicle.getBrandModel(), vehicle.getPlateHint(), vehicle.getPurchaseYear());
    }
}
