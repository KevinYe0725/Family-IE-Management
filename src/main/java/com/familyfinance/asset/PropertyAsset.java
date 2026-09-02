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
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "property_assets")
public class PropertyAsset {

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
    private AssetType assetType = AssetType.PROPERTY;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(name = "area_sqm", nullable = false, precision = 12, scale = 2)
    private BigDecimal areaSqm;

    @Column(name = "usage_type", nullable = false, length = 32)
    private String usageType;

    protected PropertyAsset() {
    }

    PropertyAsset(Asset asset, long householdId, String address, BigDecimal areaSqm, String usageType) {
        this.asset = Objects.requireNonNull(asset, "asset must not be null");
        this.householdId = householdId;
        update(address, areaSqm, usageType);
    }

    public String getAddress() { return address; }
    public BigDecimal getAreaSqm() { return areaSqm; }
    public String getUsageType() { return usageType; }

    void update(String address, BigDecimal areaSqm, String usageType) {
        this.address = Objects.requireNonNull(address, "address must not be null");
        this.areaSqm = Objects.requireNonNull(areaSqm, "areaSqm must not be null");
        this.usageType = Objects.requireNonNull(usageType, "usageType must not be null");
    }
}
