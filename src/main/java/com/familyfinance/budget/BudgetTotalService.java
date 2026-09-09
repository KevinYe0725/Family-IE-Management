package com.familyfinance.budget;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BudgetTotalService {

    private static final long MAX_AMOUNT_CENTS = 99_999_999_999L;

    private final BudgetMonthTotalRepository totals;
    private final BudgetTotalRevisionRepository revisions;
    private final BudgetRepository budgets;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public BudgetTotalService(
            BudgetMonthTotalRepository totals,
            BudgetTotalRevisionRepository revisions,
            BudgetRepository budgets,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.totals = totals;
        this.revisions = revisions;
        this.budgets = budgets;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public BudgetTotalResponse get(Authentication authentication, String rawPeriodMonth) {
        long householdId = currentMembership.require(authentication).householdId();
        String month = BudgetService.requireMonth(rawPeriodMonth).toString();
        return totals.findByHouseholdIdAndPeriodMonth(householdId, month)
                .map(BudgetTotalResponse::from)
                .orElseGet(() -> BudgetTotalResponse.absent(month));
    }

    @Transactional
    public BudgetTotalResponse save(Authentication authentication, BudgetTotalRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        String month = request == null || request.periodMonth() == null
                ? null : BudgetService.requireMonth(request.periodMonth()).toString();
        if (month == null) fields.put("periodMonth", "预算月份不能为空");
        Long amount = parseAmount(request == null ? null : request.amount(), fields);
        Integer supplied = request == null ? null : request.version();
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
        long allocated = budgets.sumAllocatedCents(householdId, month,
                BudgetScopeType.CATEGORY);
        BudgetMonthTotal current = month == null ? null
                : totals.findByHouseholdIdAndPeriodMonth(householdId, month).orElse(null);
        long now = access.context().userId();
        if (current == null) {
            if (supplied == null || supplied != 0) {
                throw staleVersion();
            }
            if (amount < allocated) {
                throw belowAllocated(allocated);
            }
            BudgetMonthTotal created = totals.saveAndFlush(new BudgetMonthTotal(
                    access.household(), month, amount, now, clock.instant()));
            return BudgetTotalResponse.from(created);
        }
        if (supplied == null || !supplied.equals(current.getVersion())) {
            throw staleVersion();
        }
        if (amount < allocated) {
            throw belowAllocated(allocated);
        }
        Long previous = current.getAmountCents();
        current.update(amount, now, clock.instant());
        revisions.saveAndFlush(new BudgetTotalRevision(householdId, month, previous, amount, now, clock.instant()));
        totals.flush();
        return BudgetTotalResponse.from(current);
    }

    static Long parseAmount(String rawAmount, Map<String, String> fields) {
        try {
            Long cents = Money.parseCents(rawAmount);
            if (cents == null || cents <= 0 || cents > MAX_AMOUNT_CENTS) {
                fields.put("amount", "金额必须大于 0 且不超过 999,999,999.99");
                return null;
            }
            return cents;
        } catch (IllegalArgumentException exception) {
            fields.put("amount", exception.getMessage());
            return null;
        }
    }

    private static ResourceConflictException staleVersion() {
        return new ResourceConflictException("STALE_VERSION", "总预算已被其他操作修改，请刷新后重试");
    }

    private static ResourceConflictException belowAllocated(long allocatedCents) {
        return new ResourceConflictException("TOTAL_BELOW_ALLOCATED",
                "总预算不能低于已分配的分类预算合计 " + Money.formatCents(allocatedCents)
                        + "，请先调大总预算或缩减分类预算");
    }
}
