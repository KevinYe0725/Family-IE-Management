package com.familyfinance.ledger.recurring;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
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
import java.time.LocalDate;

@Entity
@Table(name = "recurring_occurrences")
public class RecurringOccurrence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private RecurringRule rule;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecurringOccurrenceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_transaction_id")
    private FinancialTransaction confirmedTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_user_id")
    private AppUser assignedUser;

    protected RecurringOccurrence() {}

    RecurringOccurrence(RecurringRule rule, LocalDate dueOn) {
        this.household = rule.getHousehold();
        this.rule = rule;
        this.dueOn = dueOn;
        this.status = RecurringOccurrenceStatus.PENDING;
        this.assignedUser = rule.getAssignedUser();
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public RecurringRule getRule() { return rule; }
    public LocalDate getDueOn() { return dueOn; }
    public RecurringOccurrenceStatus getStatus() { return status; }
    public FinancialTransaction getConfirmedTransaction() { return confirmedTransaction; }
    public AppUser getAssignedUser() { return assignedUser; }

    void confirm(FinancialTransaction transaction) {
        this.confirmedTransaction = transaction;
        this.status = RecurringOccurrenceStatus.CONFIRMED;
    }

    void cancel() {
        if (status == RecurringOccurrenceStatus.PENDING) status = RecurringOccurrenceStatus.CANCELLED;
    }
}
