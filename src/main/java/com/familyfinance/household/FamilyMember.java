package com.familyfinance.household;

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

@Entity
@Table(name = "family_members")
public class FamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false)
    private String name;

    @Column(name = "role_label", nullable = false)
    private String roleLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FamilyMember() {
    }

    public FamilyMember(Household household, String name, String roleLabel, Instant createdAt) {
        this.household = household;
        this.name = name;
        this.roleLabel = roleLabel;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public String getName() {
        return name;
    }

    public String getRoleLabel() {
        return roleLabel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
