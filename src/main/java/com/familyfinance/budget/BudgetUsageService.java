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
            int page,
            int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = BudgetService.safeSize(size);
        var result = budgets.findByHouseholdIdAndPeriodMonthAndActiveTrue(
                householdId,
                periodMonth.toString(),
                PageRequest.of(safePage, safeSize, STABLE_SORT));
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
        String rawSpent = switch (budget.getScopeType()) {
            case TOTAL -> transactions.sumExpenseCentsForMonth(householdId, from, to);
            case CATEGORY -> rollupCategories
                    ? transactions.sumExpenseCentsForCategoryTree(
                            householdId, from, to, budget.getCategory().getId())
                    : transactions.sumExpenseCentsForExactCategory(
                            householdId, from, to, budget.getCategory().getId());
            case MEMBER -> transactions.sumExpenseCentsForMember(
                    householdId, from, to, budget.getMember().getId());
        };
        long spent = boundedCents(rawSpent);
        long remaining = Math.subtractExact(budget.getAmountCents(), spent);
        BigDecimal percent = BigDecimal.valueOf(spent)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(budget.getAmountCents()), 2, RoundingMode.HALF_UP);
        return new BudgetUsage(
                BudgetResponse.from(budget), spent, remaining, percent, status(percent), rollupCategories);
    }

    private static long boundedCents(String raw) {
        BigInteger value = new BigInteger(raw);
        if (value.signum() < 0 || value.compareTo(MAX_LONG) > 0) {
            throw new ResourceConflictException("AMOUNT_OVERFLOW", "预算使用额超出可计算范围");
        }
        return value.longValueExact();
    }

    private static BudgetUsageStatus status(BigDecimal percent) {
        if (percent.compareTo(BigDecimal.valueOf(100)) > 0) return BudgetUsageStatus.OVER_BUDGET;
        if (percent.compareTo(BigDecimal.valueOf(100)) == 0) return BudgetUsageStatus.AT_LIMIT;
        if (percent.compareTo(BigDecimal.valueOf(80)) >= 0) return BudgetUsageStatus.NEAR_LIMIT;
        return BudgetUsageStatus.ON_TRACK;
    }
}
