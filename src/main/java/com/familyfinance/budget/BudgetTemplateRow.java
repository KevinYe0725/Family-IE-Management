package com.familyfinance.budget;

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
import java.util.Objects;

@Entity
@Table(name = "budget_template_rows")
public class BudgetTemplateRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private Long householdId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false, updatable = false)
    private BudgetTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private BudgetScopeType scopeType;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "note", length = 200)
    private String note;

    protected BudgetTemplateRow() {
    }

    public BudgetTemplateRow(Long householdId, BudgetTemplate template, BudgetScopeType scopeType,
            Long categoryId, Long memberId, Long amountCents, String note) {
        this.householdId = Objects.requireNonNull(householdId, "householdId must not be null");
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType must not be null");
        this.categoryId = categoryId;
        this.memberId = memberId;
        this.amountCents = Objects.requireNonNull(amountCents, "amount must not be null");
        this.note = note;
    }

    public Long getId() { return id; }
    public Long getHouseholdId() { return householdId; }
    public BudgetTemplate getTemplate() { return template; }
    public BudgetScopeType getScopeType() { return scopeType; }
    public Long getCategoryId() { return categoryId; }
    public Long getMemberId() { return memberId; }
    public Long getAmountCents() { return amountCents; }
    public String getNote() { return note; }
}
