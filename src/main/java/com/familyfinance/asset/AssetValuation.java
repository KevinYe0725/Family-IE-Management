package com.familyfinance.asset;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "asset_valuations")
public class AssetValuation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false, updatable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, updatable = false)
    private Asset asset;

    @Column(name = "valued_on", nullable = false, updatable = false)
    private LocalDate valuedOn;

    @Column(name = "value_cents", nullable = false)
    private Long valueCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AssetValuationSource source;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected AssetValuation() {
    }

    AssetValuation(
            Household household,
            Asset asset,
            LocalDate valuedOn,
            long valueCents,
            AssetValuationSource source,
            String note,
            AppUser createdBy,
            Instant fetchedAt) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.asset = Objects.requireNonNull(asset, "asset must not be null");
        this.valuedOn = Objects.requireNonNull(valuedOn, "valuedOn must not be null");
        this.valueCents = valueCents;
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.note = note;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
    }

    public Long getId() { return id; }
    public LocalDate getValuedOn() { return valuedOn; }
    public Long getValueCents() { return valueCents; }
    public AssetValuationSource getSource() { return source; }
    public String getNote() { return note; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getFetchedAt() { return fetchedAt; }

    void replaceManual(long valueCents, String note, Instant fetchedAt) {
        if (source != AssetValuationSource.MANUAL) {
            throw new IllegalStateException("Only manual valuations can be replaced");
        }
        this.valueCents = valueCents;
        this.note = note;
        this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
    }
}
