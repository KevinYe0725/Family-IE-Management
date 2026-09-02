package com.familyfinance.budget;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BudgetService {

    static final int MAX_PAGE_SIZE = 50;
    private static final Sort BUDGET_SORT = Sort.by(Sort.Order.desc("periodMonth"), Sort.Order.desc("id"));
    private static final Sort REVISION_SORT = Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id"));

    private final BudgetRepository budgets;
    private final BudgetRevisionRepository revisions;
    private final CategoryRepository categories;
    private final FamilyMemberRepository members;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public BudgetService(
            BudgetRepository budgets,
            BudgetRevisionRepository revisions,
            CategoryRepository categories,
            FamilyMemberRepository members,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.budgets = budgets;
        this.revisions = revisions;
        this.categories = categories;
        this.members = members;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public BudgetPage list(Authentication authentication, String rawPeriodMonth, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = safeSize(size);
        PageRequest pageable = PageRequest.of(safePage, safeSize, BUDGET_SORT);
        var result = rawPeriodMonth == null || rawPeriodMonth.isBlank()
                ? budgets.findByHouseholdId(householdId, pageable)
                : budgets.findByHouseholdIdAndPeriodMonth(
                        householdId, requireMonth(rawPeriodMonth).toString(), pageable);
        return new BudgetPage(
                result.getContent().stream().map(BudgetResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public BudgetResponse get(Authentication authentication, long budgetId) {
        long householdId = currentMembership.require(authentication).householdId();
        return BudgetResponse.from(findOne(householdId, budgetId));
    }

    public BudgetRevisionPage revisions(Authentication authentication, long budgetId, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        findOne(householdId, budgetId);
        int safePage = Math.max(0, page);
        int safeSize = safeSize(size);
        var result = revisions.findByHouseholdIdAndBudgetId(
                householdId, budgetId, PageRequest.of(safePage, safeSize, REVISION_SORT));
        return new BudgetRevisionPage(
                result.getContent().stream().map(BudgetRevisionResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public BudgetResponse create(Authentication authentication, BudgetCreateRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        ValidatedBudget value = validateCreate(access.context().householdId(), request);
        validateUnique(access.context().householdId(), value, null);
        try {
            Budget budget = budgets.saveAndFlush(new Budget(
                    access.household(), value.periodMonth(), value.scopeType(),
                    value.category(), value.member(), value.amountCents()));
            return BudgetResponse.from(budget);
        } catch (DataIntegrityViolationException exception) {
            throw translateWriteConflict(exception);
        }
    }

    @Transactional
    public BudgetResponse update(Authentication authentication, long budgetId, BudgetPatchRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Budget budget = findOne(householdId, budgetId);
        if (request == null || request.version() == null) {
            throw validationError("version", "必须提供当前预算版本");
        }
        if (!request.version().equals(budget.getVersion())) {
            throw staleVersion();
        }
        ValidatedBudget value = validateUpdate(householdId, budget, request);
        validateUnique(householdId, value, budgetId);
        BudgetSnapshot oldValue = BudgetSnapshot.from(budget);
        budget.update(value.periodMonth(), value.scopeType(), value.category(), value.member(),
                value.amountCents(), value.active());
        BudgetSnapshot newValue = BudgetSnapshot.from(budget);
        if (oldValue.equals(newValue)) {
            return BudgetResponse.from(budget);
        }
        try {
            revisions.save(new BudgetRevision(
                    budget, oldValue, newValue, access.membership().getUser(), clock.instant()));
            budgets.flush();
            revisions.flush();
            return BudgetResponse.from(budget);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw staleVersion();
        } catch (DataIntegrityViolationException exception) {
            throw translateWriteConflict(exception);
        }
    }

    private ValidatedBudget validateCreate(long householdId, BudgetCreateRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        YearMonth month = parseMonth(request == null ? null : request.periodMonth(), fields);
        BudgetScopeType scope = request == null ? null : request.scopeType();
        if (scope == null) fields.put("scopeType", "预算范围不能为空");
        Long amount = parseAmount(request == null ? null : request.amount(), fields);
        Long categoryId = request == null ? null : request.categoryId();
        Long memberId = request == null ? null : request.memberId();
        ScopeTargets targets = resolveTargets(householdId, scope, categoryId, memberId, fields);
        throwIfInvalid(fields);
        return new ValidatedBudget(month, scope, targets.category(), targets.member(), amount, true);
    }

    private ValidatedBudget validateUpdate(long householdId, Budget budget, BudgetPatchRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        YearMonth month = request.periodMonth() == null
                ? budget.getPeriodMonth() : parseMonth(request.periodMonth(), fields);
        BudgetScopeType scope = request.scopeType() == null ? budget.getScopeType() : request.scopeType();
        Long categoryId = request.categoryId();
        Long memberId = request.memberId();
        if (scope == budget.getScopeType()) {
            if (scope == BudgetScopeType.CATEGORY && categoryId == null) categoryId = budget.getCategory().getId();
            if (scope == BudgetScopeType.MEMBER && memberId == null) memberId = budget.getMember().getId();
        }
        ScopeTargets targets = resolveTargets(householdId, scope, categoryId, memberId, fields);
        Long amount = request.amount() == null ? budget.getAmountCents() : parseAmount(request.amount(), fields);
        boolean active = request.active() == null ? budget.isActive() : request.active();
        throwIfInvalid(fields);
        return new ValidatedBudget(month, scope, targets.category(), targets.member(), amount, active);
    }

    private ScopeTargets resolveTargets(
            long householdId, BudgetScopeType scope, Long categoryId, Long memberId, Map<String, String> fields) {
        if (scope == null) return new ScopeTargets(null, null);
        if (scope == BudgetScopeType.TOTAL) {
            if (categoryId != null || memberId != null) fields.put("scopeType", "TOTAL 预算不能指定分类或成员");
            return new ScopeTargets(null, null);
        }
        if (scope == BudgetScopeType.CATEGORY) {
            if (categoryId == null || memberId != null) {
                fields.put("categoryId", "CATEGORY 预算必须且只能指定一个支出分类");
                return new ScopeTargets(null, null);
            }
            Category category = categories.findByIdAndHouseholdId(categoryId, householdId).orElse(null);
            if (category == null || category.getKind() != TransactionKind.EXPENSE) {
                fields.put("categoryId", "支出分类不存在");
                return new ScopeTargets(null, null);
            }
            return new ScopeTargets(category, null);
        }
        if (memberId == null || categoryId != null) {
            fields.put("memberId", "MEMBER 预算必须且只能指定一个成员");
            return new ScopeTargets(null, null);
        }
        FamilyMember member = members.findByIdAndHouseholdId(memberId, householdId).orElse(null);
        if (member == null) {
            fields.put("memberId", "成员不存在");
            return new ScopeTargets(null, null);
        }
        return new ScopeTargets(null, member);
    }

    private void validateUnique(long householdId, ValidatedBudget value, Long excludedId) {
        if (!value.active()) return;
        Long categoryId = value.category() == null ? null : value.category().getId();
        Long memberId = value.member() == null ? null : value.member().getId();
        boolean exists = excludedId == null
                ? budgets.existsByHouseholdIdAndPeriodMonthAndScopeTypeAndCategoryIdAndMemberIdAndActiveTrue(
                        householdId, value.periodMonth().toString(), value.scopeType(), categoryId, memberId)
                : budgets.existsByHouseholdIdAndPeriodMonthAndScopeTypeAndCategoryIdAndMemberIdAndActiveTrueAndIdNot(
                        householdId, value.periodMonth().toString(), value.scopeType(), categoryId, memberId, excludedId);
        if (exists) throw duplicateBudget();
    }

    Budget findOne(long householdId, long budgetId) {
        return budgets.findByIdAndHouseholdId(budgetId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("预算不存在"));
    }

    static YearMonth requireMonth(String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        YearMonth result = parseMonth(value, fields);
        throwIfInvalid(fields);
        return result;
    }

    private static YearMonth parseMonth(String value, Map<String, String> fields) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^\\d{4}-(0[1-9]|1[0-2])$")) {
            fields.put("periodMonth", "预算月份必须使用 YYYY-MM 格式");
            return null;
        }
        return YearMonth.parse(normalized);
    }

    private static Long parseAmount(String value, Map<String, String> fields) {
        try {
            return Money.parseCents(value);
        } catch (IllegalArgumentException exception) {
            fields.put("amount", exception.getMessage());
            return null;
        }
    }

    static int safeSize(int size) { return Math.min(MAX_PAGE_SIZE, Math.max(1, size)); }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static RequestValidationException validationError(String field, String message) {
        return new RequestValidationException(Map.of(field, message));
    }

    private static ResourceConflictException staleVersion() {
        return new ResourceConflictException("STALE_VERSION", "预算已被其他操作修改，请刷新后重试");
    }

    private static ResourceConflictException duplicateBudget() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一月份和范围只能有一个有效预算");
    }

    private static RuntimeException translateWriteConflict(DataIntegrityViolationException exception) {
        String details = exceptionMessages(exception).toLowerCase(java.util.Locale.ROOT);
        if (details.contains("uk_budgets_household_period_active_scope")) return duplicateBudget();
        return new ResourceConflictException("RESOURCE_CONFLICT", "预算关联的数据已变化，请刷新后重试");
    }

    private static String exceptionMessages(Throwable exception) {
        StringBuilder result = new StringBuilder();
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null) result.append(' ').append(current.getMessage());
            current = current.getCause();
        }
        return result.toString();
    }

    private record ScopeTargets(Category category, FamilyMember member) { }
    private record ValidatedBudget(
            YearMonth periodMonth, BudgetScopeType scopeType, Category category,
            FamilyMember member, Long amountCents, boolean active) { }
}
