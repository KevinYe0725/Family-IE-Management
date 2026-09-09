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
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "budget_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_budget_templates_household_name",
                columnNames = {"household_id", "name"}))
public class BudgetTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BudgetTemplate() {
    }

    public BudgetTemplate(Household household, String name, Long createdBy, Instant createdAt) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public String getName() { return name; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
