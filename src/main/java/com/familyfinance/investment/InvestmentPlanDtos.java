package com.familyfinance.investment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class InvestmentPlanDtos {
    private InvestmentPlanDtos() {}
    public enum Frequency { WEEKLY, BIWEEKLY, MONTHLY }
    public enum State { ACTIVE, PAUSED, ENDED }
    public enum Snooze { TWO_HOURS, TOMORROW }
    public record Request(String name, Long accountId, Long securityId, String quantity,
            Frequency frequency, LocalDate firstDueOn, Long assignedUserId) {}
    public record StateRequest(State state) {}
    public record Confirmation(String quantity, String price, String fee, LocalDate tradedOn) {}
    public record SkipRequest(String reason) {}
    public record SnoozeRequest(Snooze option) {}
    public record Plan(long id, String name, long accountId, String accountName, long fundingAccountId,
            long securityId, String securityName, String symbol, String currency, String quantity, String amount,
            Frequency frequency, LocalDate firstDueOn, LocalDate nextDueOn, long assignedUserId, State state,
            SecurityResponse security) {}
    public record Occurrence(long id, long planId, String planName, long accountId, String accountName,
            long fundingAccountId, long securityId, String securityName, String symbol, String currency,
            String quantity, String amount, LocalDate dueOn, long assignedUserId, String state, Instant remindAt,
            Long tradeId, String actualQuantity, String actualAmount, String reason, Long actedBy, Instant actedAt,
            boolean tradeReversed, InvestmentTradeResponse currentTrade) {}
    public record Page(List<Plan> plans, List<Occurrence> occurrences, boolean hasMorePlans,
            boolean hasMoreOccurrences, long pendingCount) {}
}
