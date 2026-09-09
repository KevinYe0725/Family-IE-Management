package com.familyfinance.budget;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "budget_monthly_totals",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_budget_totals_household_month",
                columnNames = {"household_id", "period_month"}))
public class BudgetMonthTotal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected BudgetMonthTotal() {
    }

    public BudgetMonthTotal(Household household, String periodMonth, Long amountCents, Long updatedBy, Instant updatedAt) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.periodMonth = Objects.requireNonNull(periodMonth, "periodMonth must not be null");
        this.amountCents = Objects.requireNonNull(amountCents, "amount must not be null");
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public String getPeriodMonth() {
        return periodMonth;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public Integer getVersion() {
        return version;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void update(Long amountCents, Long updatedBy, Instant updatedAt) {
        this.amountCents = Objects.requireNonNull(amountCents, "amount must not be null");
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }
}
