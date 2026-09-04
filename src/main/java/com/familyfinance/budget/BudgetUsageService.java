package com.familyfinance.budget;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BudgetUsageService {

    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Sort STABLE_SORT = Sort.by(Sort.Order.desc("periodMonth"), Sort.Order.desc("id"));

    private final BudgetRepository budgets;
    private final FinancialTransactionRepository transactions;
    private final CurrentMembership currentMembership;

    public BudgetUsageService(
            BudgetRepository budgets,
            FinancialTransactionRepository transactions,
            CurrentMembership currentMembership) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.currentMembership = currentMembership;
    }

    public BudgetUsagePage usage(
            Authentication authentication,
            YearMonth periodMonth,
            boolean rollupCategories,
            boolean includeInactive,
            int page,
            int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = BudgetService.safeSize(size);
        var pageable = PageRequest.of(safePage, safeSize, STABLE_SORT);
        var result = includeInactive
                ? budgets.findByHouseholdIdAndPeriodMonth(householdId, periodMonth.toString(), pageable)
                : budgets.findByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, periodMonth.toString(), pageable);
        LocalDate from = periodMonth.atDay(1);
        LocalDate to = periodMonth.plusMonths(1).atDay(1);
        var items = result.getContent().stream()
                .map(budget -> response(householdId, budget, from, to, rollupCategories))
                .map(BudgetUsageResponse::from)
                .toList();
        return new BudgetUsagePage(
                items, safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    private BudgetUsage response(
            long householdId, Budget budget, LocalDate from, LocalDate to, boolean rollupCategories) {
        Long categoryId = budget.getCategory() == null ? null : budget.getCategory().getId();
        Long memberId = budget.getMember() == null ? null : budget.getMember().getId();
        String rawSpent = transactions.sumBudgetExpenseCents(
                householdId,
                from,
                to,
                budget.getScopeType().name(),
                categoryId,
                memberId,
                rollupCategories);
        long spent = boundedCents(rawSpent);
        long remaining = Math.subtractExact(budget.getAmountCents(), spent);
        BigDecimal percent = BigDecimal.valueOf(spent)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(budget.getAmountCents()), 2, RoundingMode.HALF_UP);
        return new BudgetUsage(
                BudgetResponse.from(budget), spent, remaining, percent,
                status(spent, budget.getAmountCents()), rollupCategories);
    }

    private static long boundedCents(String raw) {
        BigInteger value = new BigInteger(raw);
        if (value.signum() < 0 || value.compareTo(MAX_LONG) > 0) {
            throw new ResourceConflictException("AMOUNT_OVERFLOW", "预算使用额超出可计算范围");
        }
        return value.longValueExact();
    }

    static BudgetUsageStatus status(long spentCents, long amountCents) {
        if (spentCents > amountCents) return BudgetUsageStatus.OVER_BUDGET;
        if (spentCents == amountCents) return BudgetUsageStatus.AT_LIMIT;
        BigInteger spentPercent = BigInteger.valueOf(spentCents).multiply(BigInteger.valueOf(100));
        BigInteger nearLimitThreshold = BigInteger.valueOf(amountCents).multiply(BigInteger.valueOf(80));
        if (spentPercent.compareTo(nearLimitThreshold) >= 0) return BudgetUsageStatus.NEAR_LIMIT;
        return BudgetUsageStatus.ON_TRACK;
    }
}
