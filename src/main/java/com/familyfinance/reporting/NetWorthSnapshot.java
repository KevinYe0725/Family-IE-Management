package com.familyfinance.reporting;

import com.familyfinance.household.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "net_worth_snapshots")
public class NetWorthSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "household_id", nullable = false) private Household household;
    @Column(name = "snapshot_on", nullable = false) private LocalDate snapshotOn;
    @Column(name = "asset_cents", nullable = false) private long assetCents;
    @Column(name = "liability_cents", nullable = false) private long liabilityCents;
    @Column(name = "net_worth_cents", nullable = false) private long netWorthCents;

    protected NetWorthSnapshot() { }

    NetWorthSnapshot(Household household, LocalDate snapshotOn, long assetCents, long liabilityCents, long netWorthCents) {
        this.household = Objects.requireNonNull(household);
        this.snapshotOn = Objects.requireNonNull(snapshotOn);
        update(assetCents, liabilityCents, netWorthCents);
    }

    public Long getId() { return id; }
    public LocalDate getSnapshotOn() { return snapshotOn; }
    public long getAssetCents() { return assetCents; }
    public long getLiabilityCents() { return liabilityCents; }
    public long getNetWorthCents() { return netWorthCents; }

    void update(long assetCents, long liabilityCents, long netWorthCents) {
        this.assetCents = assetCents;
        this.liabilityCents = liabilityCents;
        this.netWorthCents = netWorthCents;
    }
}
