package com.familyfinance.budget;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.accounting.LedgerActivity;
import com.familyfinance.accounting.LedgerReportingService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class BudgetUsageService {

    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Sort STABLE_SORT = Sort.by(Sort.Order.desc("periodMonth"), Sort.Order.desc("id"));

    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final LedgerReportingService transactions;
    private final CurrentMembership currentMembership;

    public BudgetUsageService(
            BudgetRepository budgets,
            CategoryRepository categories,
            LedgerReportingService transactions,
            CurrentMembership currentMembership) {
        this.budgets = budgets;
        this.categories = categories;
        this.transactions = transactions;
        this.currentMembership = currentMembership;
    }

    public BudgetUsagePage usage(
            Authentication authentication,
            YearMonth periodMonth,
            boolean rollupCategories,
            boolean includeInactive,
            Boolean active,
            int page,
            int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = BudgetService.safeSize(size);
        var pageable = PageRequest.of(safePage, safeSize, STABLE_SORT);
        var result = active == null
                ? includeInactive
                    ? budgets.findByHouseholdIdAndPeriodMonth(householdId, periodMonth.toString(), pageable)
                    : budgets.findByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, periodMonth.toString(), pageable)
                : active
                    ? budgets.findByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, periodMonth.toString(), pageable)
                    : budgets.findByHouseholdIdAndPeriodMonthAndActiveFalse(householdId, periodMonth.toString(), pageable);
        LocalDate from = periodMonth.atDay(1);
        LocalDate to = periodMonth.plusMonths(1).atDay(1);
        var items = result.getContent().stream()
                .map(budget -> response(householdId, budget, from, to, rollupCategories))
                .map(BudgetUsageResponse::from)
                .toList();
        return new BudgetUsagePage(
                items, safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public java.util.Map<String, String> expenseSummary(Authentication authentication, YearMonth month) {
        long householdId = currentMembership.require(authentication).householdId();
        var amount = transactions.sumBudgetExpenseAmount(householdId, month.atDay(1),
                month.plusMonths(1).atDay(1), "TOTAL", null, null, true);
        return java.util.Map.of("expense", com.familyfinance.shared.DecimalMoney.format(amount));
    }

    /** Drill-down of the effective expense entries that make up one budget's spent. */
    public BudgetUsageEntryPage usageEntries(
            Authentication authentication, long budgetId, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        Budget budget = budgets.findByIdAndHouseholdId(budgetId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("预算不存在"));
        LocalDate from = budget.getPeriodMonth().atDay(1);
        LocalDate to = budget.getPeriodMonth().plusMonths(1).atDay(1);
        List<LedgerActivity> entries = transactions.budgetEntries(
                householdId, from, to, budget.getScopeType().name(),
                budget.getCategory() == null ? null : budget.getCategory().getId(),
                budget.getMember() == null ? null : budget.getMember().getId(), true);
        int safePage = Math.max(0, page);
        int safeSize = BudgetService.safeSize(size);
        int total = entries.size();
        int start = Math.min(total, safePage * safeSize);
        int end = Math.min(total, start + safeSize);
        List<BudgetUsageEntryResponse> items = entries.subList(start, end).stream()
                .map(BudgetUsageEntryResponse::from)
                .toList();
        int totalPages = total == 0 ? 0 : (total + safeSize - 1) / safeSize;
        return new BudgetUsageEntryPage(items, safePage, safeSize, total, totalPages, end < total);
    }

    /**
     * Bookkeeping-time nudge: which active budget rows a prospective expense
     * (category plus optional member tag) would count toward, and their state after.
     */
    public List<BudgetHitResponse> hitCheck(Authentication authentication, YearMonth month,
            Long categoryId, Long memberId, long amountCents) {
        long householdId = currentMembership.require(authentication).householdId();
        Long parentCategoryId = null;
        if (categoryId != null) {
            Category expenseCategory = categories.findByIdAndHouseholdId(categoryId, householdId).orElse(null);
            if (expenseCategory != null && expenseCategory.getParent() != null) {
                parentCategoryId = expenseCategory.getParent().getId();
            }
        }
        List<BudgetHitResponse> hits = new java.util.ArrayList<>();
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        for (Budget budget : budgets.findAllByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, month.toString())) {
            BudgetScopeType scope = budget.getScopeType();
            boolean categoryMatch = scope == BudgetScopeType.CATEGORY || scope == BudgetScopeType.CATEGORY_MEMBER;
            boolean memberMatch = scope == BudgetScopeType.MEMBER || scope == BudgetScopeType.CATEGORY_MEMBER;
            Long rowCategory = budget.getCategory() == null ? null : budget.getCategory().getId();
            Long rowMember = budget.getMember() == null ? null : budget.getMember().getId();
            if (categoryMatch && !(categoryId != null
                    && (rowCategory == null || rowCategory.equals(categoryId)
                        || (parentCategoryId != null && rowCategory.equals(parentCategoryId))))) {
                continue;
            }
            if (memberMatch && !(memberId != null && rowMember != null && rowMember.equals(memberId))) {
                continue;
            }
            long spent = boundedCents(transactions.sumBudgetExpenseCents(householdId, from, to,
                    scope.name(), budget.getCategory() == null ? null : budget.getCategory().getId(),
                    budget.getMember() == null ? null : budget.getMember().getId(), true));
            long after = Math.addExact(spent, amountCents);
            BigDecimal percentAfter = BigDecimal.valueOf(after)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(budget.getAmountCents()), 2, RoundingMode.HALF_UP);
            hits.add(new BudgetHitResponse(
                    budget.getId(), scope, rowCategory,
                    budget.getCategory() == null ? null : budget.getCategory().getName(),
                    rowMember,
                    budget.getMember() == null ? null : budget.getMember().getName(),
                    com.familyfinance.shared.Money.formatCents(budget.getAmountCents()),
                    com.familyfinance.shared.Money.formatCents(spent),
                    com.familyfinance.shared.Money.formatCents(after),
                    percentAfter, status(after, budget.getAmountCents())));
        }
        return List.copyOf(hits);
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

    /** Budgets-and-execution CSV for one month (BOM-prefixed for spreadsheet tools). */
    public String exportCsv(Authentication authentication, YearMonth month, boolean includeInactive) {
        long householdId = currentMembership.require(authentication).householdId();
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        StringBuilder out = new StringBuilder("\uFEFF");
        out.append("月份,类型,分类,成员,预算金额,已用,剩余,执行率(%),状态,启用,备注\r\n");
        List<Budget> rows = includeInactive
                ? budgets.findAllByHouseholdIdAndPeriodMonth(householdId, month.toString())
                : budgets.findAllByHouseholdIdAndPeriodMonthAndActiveTrue(householdId, month.toString());
        for (Budget budget : rows) {
            BudgetUsage usage = response(householdId, budget, from, to, true);
            out.append(csv(budget.getPeriodMonth().toString())).append(',')
                .append(csv(scopeLabel(budget.getScopeType()))).append(',')
                .append(csv(budget.getCategory() == null ? "" : budget.getCategory().getName())).append(',')
                .append(csv(budget.getMember() == null ? "" : budget.getMember().getName())).append(',')
                .append(csv(com.familyfinance.shared.Money.formatCents(budget.getAmountCents()))).append(',')
                .append(csv(com.familyfinance.shared.Money.formatCents(usage.spentCents()))).append(',')
                .append(csv(com.familyfinance.shared.Money.formatCents(usage.remainingCents()))).append(',')
                .append(csv(usage.percent().toPlainString())).append(',')
                .append(csv(statusLabel(usage.status()))).append(',')
                .append(budget.isActive() ? "是" : "否").append(',')
                .append(csv(budget.getNote())).append("\r\n");
        }
        return out.toString();
    }

    private static String scopeLabel(BudgetScopeType scope) {
        return switch (scope) {
            case CATEGORY -> "分类预算";
            case MEMBER -> "成员观察线";
            case CATEGORY_MEMBER -> "分类+成员观察线";
            case TOTAL -> "家庭总预算";
        };
    }

    private static String statusLabel(BudgetUsageStatus status) {
        return switch (status) {
            case ON_TRACK -> "进度正常";
            case NEAR_LIMIT -> "接近额度";
            case AT_LIMIT -> "已用完";
            case OVER_BUDGET -> "已超支";
        };
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
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
