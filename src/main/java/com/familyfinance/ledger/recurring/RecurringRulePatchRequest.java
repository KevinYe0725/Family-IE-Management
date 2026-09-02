package com.familyfinance.ledger.recurring;

import com.familyfinance.category.TransactionKind;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.time.DayOfWeek;
import java.time.LocalDate;

public final class RecurringRulePatchRequest {
    private TransactionKind kind;
    private String amount;
    private RecurringScheduleType scheduleType;
    private Integer intervalValue;
    private Integer dayOfMonth;
    private DayOfWeek dayOfWeek;
    private LocalDate startOn;
    private LocalDate endOn;
    private boolean endOnPresent;
    private Long accountId;
    private Long memberId;
    private Long categoryId;
    private Long assignedUserId;
    private Boolean paused;

    public TransactionKind kind() { return kind; }
    public String amount() { return amount; }
    public RecurringScheduleType scheduleType() { return scheduleType; }
    public Integer intervalValue() { return intervalValue; }
    public Integer dayOfMonth() { return dayOfMonth; }
    public DayOfWeek dayOfWeek() { return dayOfWeek; }
    public LocalDate startOn() { return startOn; }
    public LocalDate endOn() { return endOn; }
    public boolean endOnPresent() { return endOnPresent; }
    public Long accountId() { return accountId; }
    public Long memberId() { return memberId; }
    public Long categoryId() { return categoryId; }
    public Long assignedUserId() { return assignedUserId; }
    public Boolean paused() { return paused; }

    public void setKind(TransactionKind kind) { this.kind = kind; }
    public void setAmount(String amount) { this.amount = amount; }
    public void setScheduleType(RecurringScheduleType scheduleType) { this.scheduleType = scheduleType; }
    public void setIntervalValue(Integer intervalValue) { this.intervalValue = intervalValue; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public void setDayOfWeek(DayOfWeek dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public void setStartOn(LocalDate startOn) { this.startOn = startOn; }

    @JsonSetter("endOn")
    public void setEndOn(LocalDate endOn) {
        this.endOnPresent = true;
        this.endOn = endOn;
    }

    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public void setAssignedUserId(Long assignedUserId) { this.assignedUserId = assignedUserId; }
    public void setPaused(Boolean paused) { this.paused = paused; }
}
