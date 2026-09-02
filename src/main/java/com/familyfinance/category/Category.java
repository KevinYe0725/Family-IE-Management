package com.familyfinance.category;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_categories_household_kind_name",
                columnNames = {"household_id", "kind", "name"}))
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionKind kind;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String color;

    @Column(name = "is_default", nullable = false)
    private boolean defaultCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {
    }

    public Category(
            Household household,
            TransactionKind kind,
            String name,
            String color,
            boolean defaultCategory,
            Instant createdAt) {
        this(household, kind, name, color, defaultCategory, null, createdAt);
    }

    public Category(
            Household household,
            TransactionKind kind,
            String name,
            String color,
            boolean defaultCategory,
            Category parent,
            Instant createdAt) {
        requireValidHierarchy(this, household, kind, parent);
        this.household = household;
        this.kind = kind;
        this.name = name;
        this.color = color;
        this.defaultCategory = defaultCategory;
        this.parent = parent;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public TransactionKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public boolean isDefaultCategory() {
        return defaultCategory;
    }

    public Category getParent() {
        return parent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(TransactionKind kind, String name, String color) {
        update(kind, name, color, parent);
    }

    public void update(TransactionKind kind, String name, String color, Category parent) {
        requireValidHierarchy(this, household, kind, parent);
        this.kind = kind;
        this.name = name;
        this.color = color;
        this.parent = parent;
    }

    private static void requireValidHierarchy(
            Category child, Household household, TransactionKind kind, Category parent) {
        Objects.requireNonNull(household, "household must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (parent == null) {
            return;
        }
        if (parent == child || (child.id != null && Objects.equals(child.id, parent.getId()))) {
            throw new IllegalArgumentException("Category cannot be its own parent");
        }
        if (!sameHousehold(household, parent.household)) {
            throw new IllegalArgumentException("Parent category must belong to the child category household");
        }
        if (parent.kind != kind) {
            throw new IllegalArgumentException("Parent category kind must match child category kind");
        }
        if (parent.parent != null) {
            throw new IllegalArgumentException("Category hierarchy supports at most two levels");
        }
    }

    private static boolean sameHousehold(Household expected, Household actual) {
        if (expected == actual) {
            return true;
        }
        return expected.getId() != null && Objects.equals(expected.getId(), actual.getId());
    }
}
