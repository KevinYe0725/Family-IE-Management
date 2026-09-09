package com.familyfinance.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "budget_total_revisions")
@Immutable
public class BudgetTotalRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private Long householdId;

    @Column(name = "period_month", nullable = false, length = 7, updatable = false)
    private String periodMonth;

    @Column(name = "old_amount_cents", nullable = false, updatable = false)
    private Long oldAmountCents;

    @Column(name = "new_amount_cents", nullable = false, updatable = false)
    private Long newAmountCents;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected BudgetTotalRevision() {
    }

    public BudgetTotalRevision(Long householdId, String periodMonth, Long oldAmountCents, Long newAmountCents,
            Long changedBy, Instant changedAt) {
        this.householdId = householdId;
        this.periodMonth = periodMonth;
        this.oldAmountCents = oldAmountCents;
        this.newAmountCents = newAmountCents;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public Long getId() { return id; }
    public Long getHouseholdId() { return householdId; }
    public String getPeriodMonth() { return periodMonth; }
    public Long getOldAmountCents() { return oldAmountCents; }
    public Long getNewAmountCents() { return newAmountCents; }
    public Long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}
