package com.familyfinance.transaction;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
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
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "financial_transactions")
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionKind kind;

    @Positive
    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    private String merchant;

    private String location;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancialTransaction() {
    }

    public FinancialTransaction(
            Household household,
            FamilyMember member,
            Category category,
            TransactionKind kind,
            Long amountCents,
            LocalDate occurredOn,
            String merchant,
            String location,
            String note,
            Instant createdAt,
            Instant updatedAt) {
        requireValidTransactionScope(household, member, category, kind);
        this.household = household;
        this.member = member;
        this.category = category;
        this.kind = kind;
        this.amountCents = amountCents;
        this.occurredOn = occurredOn;
        this.merchant = merchant;
        this.location = location;
        this.note = note;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public FamilyMember getMember() {
        return member;
    }

    public Category getCategory() {
        return category;
    }

    public TransactionKind getKind() {
        return kind;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getLocation() {
        return location;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void updateDetails(
            FamilyMember member,
            Category category,
            TransactionKind kind,
            Long amountCents,
            LocalDate occurredOn,
            String merchant,
            String location,
            String note,
            Instant updatedAt) {
        requireValidTransactionScope(household, member, category, kind);
        this.member = member;
        this.category = category;
        this.kind = kind;
        this.amountCents = amountCents;
        this.occurredOn = occurredOn;
        this.merchant = merchant;
        this.location = location;
        this.note = note;
        this.updatedAt = updatedAt;
    }

    private static void requireValidTransactionScope(
            Household household,
            FamilyMember member,
            Category category,
            TransactionKind kind) {
        Objects.requireNonNull(household, "household must not be null");
        Objects.requireNonNull(member, "member must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (!sameHousehold(household, member.getHousehold())) {
            throw new IllegalArgumentException("Transaction member must belong to the transaction household");
        }
        if (!sameHousehold(household, category.getHousehold())) {
            throw new IllegalArgumentException("Transaction category must belong to the transaction household");
        }
        if (category.getKind() != kind) {
            throw new IllegalArgumentException("Transaction category kind must match transaction kind");
        }
    }

    private static boolean sameHousehold(Household expected, Household actual) {
        if (expected == actual) {
            return true;
        }
        return expected.getId() != null && Objects.equals(expected.getId(), actual.getId());
    }
}
