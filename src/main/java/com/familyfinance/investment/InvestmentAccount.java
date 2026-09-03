package com.familyfinance.investment;

import com.familyfinance.household.AppUser;
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
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "investment_accounts")
public class InvestmentAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "broker_name", nullable = false, length = 100)
    private String brokerName;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    protected InvestmentAccount() {
    }

    InvestmentAccount(Household household, String name, String brokerName, AppUser createdBy) {
        this.household = Objects.requireNonNull(household);
        this.name = Objects.requireNonNull(name);
        this.brokerName = Objects.requireNonNull(brokerName);
        this.currency = "CNY";
        this.createdBy = Objects.requireNonNull(createdBy);
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public String getName() { return name; }
    public String getBrokerName() { return brokerName; }
    public String getCurrency() { return currency; }
    public Instant getArchivedAt() { return archivedAt; }
    public AppUser getCreatedBy() { return createdBy; }
    public boolean isArchived() { return archivedAt != null; }

    void update(String name, String brokerName) {
        this.name = Objects.requireNonNull(name);
        this.brokerName = Objects.requireNonNull(brokerName);
    }

    void archive(Instant when) {
        if (archivedAt == null) archivedAt = Objects.requireNonNull(when);
    }
}
