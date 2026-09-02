package com.familyfinance.ledger.recurring;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import com.familyfinance.ledger.FinancialAccount;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "recurring_rules")
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionKind kind;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 16)
    private RecurringScheduleType scheduleType;

    @Column(name = "interval_value", nullable = false)
    private Integer intervalValue;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 9)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_on", nullable = false)
    private LocalDate startOn;

    @Column(name = "end_on")
    private LocalDate endOn;

    @Column(name = "next_due_on")
    private LocalDate nextDueOn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private FinancialAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_user_id", nullable = false)
    private AppUser assignedUser;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean paused;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    protected RecurringRule() {}

    public RecurringRule(
            Household household,
            TransactionKind kind,
            long amountCents,
            RecurringScheduleType scheduleType,
            int intervalValue,
            Integer dayOfMonth,
            DayOfWeek dayOfWeek,
            LocalDate startOn,
            LocalDate endOn,
            LocalDate nextDueOn,
            FinancialAccount account,
            FamilyMember member,
            Category category,
            AppUser assignedUser,
            boolean paused,
            AppUser createdBy) {
        this.household = Objects.requireNonNull(household);
        this.kind = Objects.requireNonNull(kind);
        this.amountCents = amountCents;
        this.scheduleType = Objects.requireNonNull(scheduleType);
        this.intervalValue = intervalValue;
        this.dayOfMonth = dayOfMonth;
        this.dayOfWeek = dayOfWeek;
        this.startOn = Objects.requireNonNull(startOn);
        this.endOn = endOn;
        this.nextDueOn = nextDueOn;
        this.account = Objects.requireNonNull(account);
        this.member = Objects.requireNonNull(member);
        this.category = Objects.requireNonNull(category);
        this.assignedUser = Objects.requireNonNull(assignedUser);
        this.paused = paused;
        this.createdBy = Objects.requireNonNull(createdBy);
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public TransactionKind getKind() { return kind; }
    public Long getAmountCents() { return amountCents; }
    public RecurringScheduleType getScheduleType() { return scheduleType; }
    public Integer getIntervalValue() { return intervalValue; }
    public Integer getDayOfMonth() { return dayOfMonth; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public LocalDate getStartOn() { return startOn; }
    public LocalDate getEndOn() { return endOn; }
    public LocalDate getNextDueOn() { return nextDueOn; }
    public FinancialAccount getAccount() { return account; }
    public FamilyMember getMember() { return member; }
    public Category getCategory() { return category; }
    public AppUser getAssignedUser() { return assignedUser; }
    public boolean isActive() { return active; }
    public boolean isPaused() { return paused; }
    public AppUser getCreatedBy() { return createdBy; }

    void update(
            TransactionKind kind,
            long amountCents,
            RecurringScheduleType scheduleType,
            int intervalValue,
            Integer dayOfMonth,
            DayOfWeek dayOfWeek,
            LocalDate startOn,
            LocalDate endOn,
            LocalDate nextDueOn,
            FinancialAccount account,
            FamilyMember member,
            Category category,
            AppUser assignedUser,
            boolean paused) {
        this.kind = kind;
        this.amountCents = amountCents;
        this.scheduleType = scheduleType;
        this.intervalValue = intervalValue;
        this.dayOfMonth = dayOfMonth;
        this.dayOfWeek = dayOfWeek;
        this.startOn = startOn;
        this.endOn = endOn;
        this.nextDueOn = nextDueOn;
        this.account = account;
        this.member = member;
        this.category = category;
        this.assignedUser = assignedUser;
        this.paused = paused;
    }

    void advanceTo(LocalDate nextDueOn) { this.nextDueOn = nextDueOn; }
    void archive() { this.active = false; this.paused = false; this.nextDueOn = null; }
}
