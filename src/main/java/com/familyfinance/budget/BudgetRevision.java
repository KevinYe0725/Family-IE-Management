package com.familyfinance.budget;

import com.familyfinance.household.AppUser;
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
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "budget_revisions")
@Immutable
public class BudgetRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private Long householdId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "budget_id", nullable = false, updatable = false)
    private Budget budget;

    @Column(name = "old_period_month", nullable = false, length = 7, updatable = false)
    private String oldPeriodMonth;

    @Column(name = "new_period_month", nullable = false, length = 7, updatable = false)
    private String newPeriodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_scope_type", nullable = false, length = 16, updatable = false)
    private BudgetScopeType oldScopeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_scope_type", nullable = false, length = 16, updatable = false)
    private BudgetScopeType newScopeType;

    @Column(name = "old_category_id", updatable = false)
    private Long oldCategoryId;

    @Column(name = "new_category_id", updatable = false)
    private Long newCategoryId;

    @Column(name = "old_member_id", updatable = false)
    private Long oldMemberId;

    @Column(name = "new_member_id", updatable = false)
    private Long newMemberId;

    @Column(name = "old_amount_cents", nullable = false, updatable = false)
    private Long oldAmountCents;

    @Column(name = "new_amount_cents", nullable = false, updatable = false)
    private Long newAmountCents;

    @Column(name = "old_active", nullable = false, updatable = false)
    private boolean oldActive;

    @Column(name = "new_active", nullable = false, updatable = false)
    private boolean newActive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false, updatable = false)
    private AppUser changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected BudgetRevision() {
    }

    BudgetRevision(Budget budget, BudgetSnapshot oldValue, BudgetSnapshot newValue, AppUser actor, Instant changedAt) {
        this.householdId = budget.getHousehold().getId();
        this.budget = budget;
        this.oldPeriodMonth = oldValue.periodMonth();
        this.newPeriodMonth = newValue.periodMonth();
        this.oldScopeType = oldValue.scopeType();
        this.newScopeType = newValue.scopeType();
        this.oldCategoryId = oldValue.categoryId();
        this.newCategoryId = newValue.categoryId();
        this.oldMemberId = oldValue.memberId();
        this.newMemberId = newValue.memberId();
        this.oldAmountCents = oldValue.amountCents();
        this.newAmountCents = newValue.amountCents();
        this.oldActive = oldValue.active();
        this.newActive = newValue.active();
        this.changedBy = actor;
        this.changedAt = changedAt;
    }

    public Long getId() { return id; }
    public Budget getBudget() { return budget; }
    public String getOldPeriodMonth() { return oldPeriodMonth; }
    public String getNewPeriodMonth() { return newPeriodMonth; }
    public BudgetScopeType getOldScopeType() { return oldScopeType; }
    public BudgetScopeType getNewScopeType() { return newScopeType; }
    public Long getOldCategoryId() { return oldCategoryId; }
    public Long getNewCategoryId() { return newCategoryId; }
    public Long getOldMemberId() { return oldMemberId; }
    public Long getNewMemberId() { return newMemberId; }
    public Long getOldAmountCents() { return oldAmountCents; }
    public Long getNewAmountCents() { return newAmountCents; }
    public boolean isOldActive() { return oldActive; }
    public boolean isNewActive() { return newActive; }
    public AppUser getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}
