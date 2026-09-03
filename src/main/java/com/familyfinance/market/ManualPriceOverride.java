package com.familyfinance.market;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import com.familyfinance.investment.Security;
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
@Table(name = "manual_price_overrides")
public class ManualPriceOverride {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "household_id") private Household household;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "security_id") private Security security;
    @Column(name = "price_cents", nullable = false) private long priceCents;
    @Column(name = "effective_on", nullable = false) private LocalDate effectiveOn;
    @Column(length = 500) private String note;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private AppUser createdBy;
    protected ManualPriceOverride() { }
    ManualPriceOverride(Household household, Security security, long priceCents, LocalDate effectiveOn, String note, AppUser createdBy) {
        this.household = Objects.requireNonNull(household); this.security = Objects.requireNonNull(security);
        this.priceCents = priceCents; this.effectiveOn = Objects.requireNonNull(effectiveOn); this.note = note;
        this.createdBy = Objects.requireNonNull(createdBy);
    }
    void replace(long priceCents, String note) { this.priceCents = priceCents; this.note = note; }
    public LocalDate getEffectiveOn() { return effectiveOn; }
    public long getPriceCents() { return priceCents; }
    public String getNote() { return note; }
}
