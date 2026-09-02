package com.familyfinance.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "vehicle_assets")
public class VehicleAsset {

    @Id
    @Column(name = "asset_id")
    private Long assetId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Column(name = "household_id", nullable = false, updatable = false)
    private Long householdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 16, updatable = false)
    private AssetType assetType = AssetType.VEHICLE;

    @Column(name = "brand_model", nullable = false, length = 120)
    private String brandModel;

    @Column(name = "plate_hint", length = 32)
    private String plateHint;

    @Column(name = "purchase_year")
    private Integer purchaseYear;

    protected VehicleAsset() {
    }

    VehicleAsset(Asset asset, long householdId, String brandModel, String plateHint, Integer purchaseYear) {
        this.asset = Objects.requireNonNull(asset, "asset must not be null");
        this.householdId = householdId;
        update(brandModel, plateHint, purchaseYear);
    }

    public String getBrandModel() { return brandModel; }
    public String getPlateHint() { return plateHint; }
    public Integer getPurchaseYear() { return purchaseYear; }

    void update(String brandModel, String plateHint, Integer purchaseYear) {
        this.brandModel = Objects.requireNonNull(brandModel, "brandModel must not be null");
        this.plateHint = plateHint;
        this.purchaseYear = purchaseYear;
    }
}
