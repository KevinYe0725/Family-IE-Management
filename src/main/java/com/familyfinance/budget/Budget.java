package com.familyfinance.budget;

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
import jakarta.persistence.Version;
import java.time.YearMonth;
import java.util.Objects;

@Entity
@Table(name = "budgets")
public class Budget {

    private static final long MAX_AMOUNT_CENTS = 99_999_999_999L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private BudgetScopeType scopeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private FamilyMember member;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Version
    @Column(nullable = false)
    private Integer version = 1;

    @Column(nullable = false)
    private boolean active = true;

    protected Budget() {
    }

    public Budget(
            Household household,
            YearMonth periodMonth,
            BudgetScopeType scopeType,
            Category category,
            FamilyMember member,
            Long amountCents) {
        requireValidScope(household, scopeType, category, member, amountCents);
        this.household = household;
        this.periodMonth = Objects.requireNonNull(periodMonth).toString();
        this.scopeType = Objects.requireNonNull(scopeType);
        this.category = category;
        this.member = member;
        this.amountCents = Objects.requireNonNull(amountCents);
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public YearMonth getPeriodMonth() { return YearMonth.parse(periodMonth); }
    public BudgetScopeType getScopeType() { return scopeType; }
    public Category getCategory() { return category; }
    public FamilyMember getMember() { return member; }
    public Long getAmountCents() { return amountCents; }
    public Integer getVersion() { return version; }
    public boolean isActive() { return active; }

    void update(
            YearMonth periodMonth,
            BudgetScopeType scopeType,
            Category category,
            FamilyMember member,
            Long amountCents,
            boolean active) {
        requireValidScope(household, scopeType, category, member, amountCents);
        this.periodMonth = Objects.requireNonNull(periodMonth).toString();
        this.scopeType = Objects.requireNonNull(scopeType);
        this.category = category;
        this.member = member;
        this.amountCents = Objects.requireNonNull(amountCents);
        this.active = active;
    }

    private static void requireValidScope(
            Household household,
            BudgetScopeType scopeType,
            Category category,
            FamilyMember member,
            Long amountCents) {
        Objects.requireNonNull(household, "household must not be null");
        Objects.requireNonNull(scopeType, "scope type must not be null");
        Objects.requireNonNull(amountCents, "amount must not be null");
        if (amountCents <= 0 || amountCents > MAX_AMOUNT_CENTS) {
            throw new IllegalArgumentException("Budget amount is outside the supported positive range");
        }
        if (scopeType == BudgetScopeType.TOTAL && (category != null || member != null)) {
            throw new IllegalArgumentException("Total budgets cannot have a category or member target");
        }
        if (scopeType == BudgetScopeType.CATEGORY) {
            if (category == null || member != null || category.getKind() != TransactionKind.EXPENSE) {
                throw new IllegalArgumentException("Category budgets require exactly one expense category");
            }
            if (!sameHousehold(household, category.getHousehold())) {
                throw new IllegalArgumentException("Budget category must belong to its household");
            }
        }
        if (scopeType == BudgetScopeType.MEMBER) {
            if (member == null || category != null) {
                throw new IllegalArgumentException("Member budgets require exactly one member");
            }
            if (!sameHousehold(household, member.getHousehold())) {
                throw new IllegalArgumentException("Budget member must belong to its household");
            }
        }
    }

    private static boolean sameHousehold(Household expected, Household actual) {
        return expected == actual
                || (expected.getId() != null && Objects.equals(expected.getId(), actual.getId()));
    }
}
