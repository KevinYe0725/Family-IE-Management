package com.familyfinance.ledger.recurring;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.AppUserStatus;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RecurringService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final long MAX_AMOUNT_CENTS = 99_999_999_999L;
    private static final int MAX_GENERATED_PER_RULE = 10_000;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Sort RULE_SORT = Sort.by(Sort.Direction.DESC, "id");
    private static final Sort OCCURRENCE_SORT = Sort.by(
            Sort.Order.asc("dueOn"), Sort.Order.asc("id"));

    private final RecurringRuleRepository rules;
    private final RecurringOccurrenceRepository occurrences;
    private final FinancialAccountRepository accounts;
    private final FamilyMemberRepository members;
    private final CategoryRepository categories;
    private final AppUserRepository users;
    private final HouseholdMembershipRepository memberships;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public RecurringService(
            RecurringRuleRepository rules,
            RecurringOccurrenceRepository occurrences,
            FinancialAccountRepository accounts,
            FamilyMemberRepository members,
            CategoryRepository categories,
            AppUserRepository users,
            HouseholdMembershipRepository memberships,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.rules = rules;
        this.occurrences = occurrences;
        this.accounts = accounts;
        this.members = members;
        this.categories = categories;
        this.users = users;
        this.memberships = memberships;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public RecurringRulePage listRules(
            Authentication authentication, boolean includeInactive, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        PageRequest request = PageRequest.of(safePage, safeSize, RULE_SORT);
        var result = includeInactive
                ? rules.findByHouseholdId(householdId, request)
                : rules.findByHouseholdIdAndActiveTrue(householdId, request);
        return new RecurringRulePage(
                result.getContent().stream().map(RecurringRuleResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public RecurringOccurrencePage listOccurrences(
            Authentication authentication,
            RecurringOccurrenceStatus status,
            LocalDate from,
            LocalDate to,
            Long assignedUserId,
            int page,
            int size) {
        long householdId = currentMembership.require(authentication).householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        if (from != null && to != null && from.isAfter(to)) fields.put("to", "结束日期不能早于开始日期");
        if (assignedUserId != null && users.findByIdAndHouseholdId(assignedUserId, householdId).isEmpty()) {
            fields.put("assignedUserId", "分配用户不存在");
        }
        throwIfInvalid(fields);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = occurrences.findAll((root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("household").get("id"), householdId));
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("dueOn"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("dueOn"), to));
            if (assignedUserId != null) {
                predicates.add(builder.equal(root.get("assignedUser").get("id"), assignedUserId));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(safePage, safeSize, OCCURRENCE_SORT));
        return new RecurringOccurrencePage(
                result.getContent().stream().map(RecurringOccurrenceResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public RecurringRuleResponse create(Authentication authentication, RecurringRuleRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        ValidatedRule data = validateCreate(householdId, request, fields);
        throwIfInvalid(fields);
        LocalDate nextDue = firstDue(data.scheduleType(), data.dayOfMonth(), data.dayOfWeek(), data.startOn());
        nextDue = withinEnd(nextDue, data.endOn());
        try {
            RecurringRule saved = rules.saveAndFlush(new RecurringRule(
                    access.household(), data.kind(), data.amountCents(), data.scheduleType(), data.intervalValue(),
                    data.dayOfMonth(), data.dayOfWeek(), data.startOn(), data.endOn(), nextDue,
                    data.account(), data.member(), data.category(), data.assignedUser(), data.paused(),
                    access.membership().getUser()));
            return RecurringRuleResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            throw staleWrite();
        }
    }

    @Transactional
    public RecurringRuleResponse update(
            Authentication authentication, long ruleId, RecurringRulePatchRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        RecurringRule rule = findRule(householdId, ruleId);
        if (!rule.isActive()) throw new ResourceConflictException("RULE_ARCHIVED", "周期规则已归档");
        Map<String, String> fields = new LinkedHashMap<>();
        TransactionKind kind = request == null || request.kind() == null ? rule.getKind() : request.kind();
        long amount = request == null || request.amount() == null
                ? rule.getAmountCents() : parseAmount(request.amount(), fields);
        RecurringScheduleType scheduleType = request == null || request.scheduleType() == null
                ? rule.getScheduleType() : request.scheduleType();
        int interval = request == null || request.intervalValue() == null
                ? rule.getIntervalValue() : request.intervalValue();
        Integer dayOfMonth = request == null || request.dayOfMonth() == null
                ? rule.getDayOfMonth() : request.dayOfMonth();
        DayOfWeek dayOfWeek = request == null || request.dayOfWeek() == null
                ? rule.getDayOfWeek() : request.dayOfWeek();
        if (scheduleType == RecurringScheduleType.MONTHLY) dayOfWeek = null;
        if (scheduleType == RecurringScheduleType.WEEKLY) dayOfMonth = null;
        LocalDate startOn = request == null || request.startOn() == null ? rule.getStartOn() : request.startOn();
        LocalDate endOn = request == null || request.endOn() == null ? rule.getEndOn() : request.endOn();
        Long accountId = request == null || request.accountId() == null
                ? rule.getAccount().getId() : request.accountId();
        Long memberId = request == null || request.memberId() == null
                ? rule.getMember().getId() : request.memberId();
        Long categoryId = request == null || request.categoryId() == null
                ? rule.getCategory().getId() : request.categoryId();
        Long assigneeId = request == null || request.assignedUserId() == null
                ? rule.getAssignedUser().getId() : request.assignedUserId();
        FinancialAccount account = resolveActiveAccount(householdId, accountId, fields);
        FamilyMember member = resolveMember(householdId, memberId, fields);
        Category category = resolveCategory(householdId, categoryId, kind, fields);
        AppUser assignee = resolveActiveAssignedUser(householdId, assigneeId, fields);
        boolean paused = request != null && request.paused() != null ? request.paused() : rule.isPaused();
        validateSchedule(scheduleType, interval, dayOfMonth, dayOfWeek, startOn, endOn, fields);
        if (category != null && category.getKind() != kind) fields.put("categoryId", "分类类型必须和收支类型一致");
        throwIfInvalid(fields);
        boolean scheduleChanged = request != null && (request.scheduleType() != null
                || request.intervalValue() != null || request.dayOfMonth() != null
                || request.dayOfWeek() != null || request.startOn() != null || request.endOn() != null);
        LocalDate nextDue = scheduleChanged
                ? withinEnd(firstDue(scheduleType, dayOfMonth, dayOfWeek, startOn), endOn)
                : withinEnd(rule.getNextDueOn(), endOn);
        try {
            rule.update(kind, amount, scheduleType, interval, dayOfMonth, dayOfWeek, startOn, endOn, nextDue,
                    account, member, category, assignee, paused);
            rules.flush();
            return RecurringRuleResponse.from(rule);
        } catch (DataIntegrityViolationException exception) {
            throw staleWrite();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long ruleId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        RecurringRule rule = findRule(access.context().householdId(), ruleId);
        if (!rule.isActive()) return;
        rule.archive();
        occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId).forEach(RecurringOccurrence::cancel);
        try {
            rules.flush();
            occurrences.flush();
        } catch (DataIntegrityViolationException exception) {
            throw staleWrite();
        }
    }

    @Transactional
    public int generateDueOccurrences() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        int created = 0;
        for (RecurringRule rule : rules
                .findByActiveTrueAndPausedFalseAndNextDueOnLessThanEqualOrderByIdAsc(today)) {
            if (rule.getEndOn() != null && rule.getEndOn().isBefore(today)) {
                rule.advanceTo(null);
                continue;
            }
            int generatedForRule = 0;
            LocalDate due = rule.getNextDueOn();
            while (due != null && !due.isAfter(today) && withinEnd(due, rule.getEndOn()) != null) {
                if (!occurrences.existsByRuleIdAndDueOn(rule.getId(), due)) {
                    occurrences.save(new RecurringOccurrence(rule, due));
                    created++;
                }
                if (++generatedForRule > MAX_GENERATED_PER_RULE) {
                    throw new ResourceConflictException("RECURRENCE_RANGE_TOO_LARGE", "周期规则待生成范围过大");
                }
                due = nextDue(rule, due);
            }
            rule.advanceTo(withinEnd(due, rule.getEndOn()));
        }
        try {
            occurrences.flush();
            rules.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RECURRENCE_RACE", "周期发生项已由另一任务生成，请重试");
        }
        return created;
    }

    private ValidatedRule validateCreate(long householdId, RecurringRuleRequest request, Map<String, String> fields) {
        TransactionKind kind = request == null ? null : request.kind();
        if (kind == null) fields.put("kind", "收支类型不能为空");
        long amount = parseAmount(request == null ? null : request.amount(), fields);
        RecurringScheduleType schedule = request == null ? null : request.scheduleType();
        int interval = request == null || request.intervalValue() == null ? 0 : request.intervalValue();
        Integer dayOfMonth = request == null ? null : request.dayOfMonth();
        DayOfWeek dayOfWeek = request == null ? null : request.dayOfWeek();
        LocalDate start = request == null ? null : request.startOn();
        LocalDate end = request == null ? null : request.endOn();
        validateSchedule(schedule, interval, dayOfMonth, dayOfWeek, start, end, fields);
        FinancialAccount account = resolveActiveAccount(householdId, request == null ? null : request.accountId(), fields);
        FamilyMember member = resolveMember(householdId, request == null ? null : request.memberId(), fields);
        Category category = resolveCategory(householdId, request == null ? null : request.categoryId(), kind, fields);
        AppUser assignee = resolveActiveAssignedUser(
                householdId, request == null ? null : request.assignedUserId(), fields);
        return new ValidatedRule(kind, amount, schedule, interval, dayOfMonth, dayOfWeek, start, end,
                account, member, category, assignee, request != null && Boolean.TRUE.equals(request.paused()));
    }

    private FinancialAccount resolveActiveAccount(long householdId, Long id, Map<String, String> fields) {
        if (id == null) { fields.put("accountId", "账户不能为空"); return null; }
        return accounts.findByIdAndHouseholdIdAndArchivedAtIsNull(id, householdId).orElseGet(() -> {
            fields.put("accountId", "账户不存在"); return null;
        });
    }

    private FamilyMember resolveMember(long householdId, Long id, Map<String, String> fields) {
        if (id == null) { fields.put("memberId", "成员不能为空"); return null; }
        return members.findByIdAndHouseholdId(id, householdId).orElseGet(() -> {
            fields.put("memberId", "成员不存在"); return null;
        });
    }

    private Category resolveCategory(
            long householdId, Long id, TransactionKind kind, Map<String, String> fields) {
        if (id == null) { fields.put("categoryId", "分类不能为空"); return null; }
        Category category = categories.findByIdAndHouseholdId(id, householdId).orElse(null);
        if (category == null) fields.put("categoryId", "分类不存在");
        else if (kind != null && category.getKind() != kind) fields.put("categoryId", "分类类型必须和收支类型一致");
        return category;
    }

    private AppUser resolveActiveAssignedUser(long householdId, Long id, Map<String, String> fields) {
        if (id == null) { fields.put("assignedUserId", "分配用户不能为空"); return null; }
        AppUser user = users.findByIdAndHouseholdIdAndStatus(id, householdId, AppUserStatus.ACTIVE).orElse(null);
        if (user == null || memberships
                .findByHouseholdIdAndUserIdAndStatus(householdId, id, MembershipStatus.ACTIVE).isEmpty()) {
            fields.put("assignedUserId", "分配用户不存在或未激活");
            return null;
        }
        return user;
    }

    private static void validateSchedule(
            RecurringScheduleType type,
            int interval,
            Integer dayOfMonth,
            DayOfWeek dayOfWeek,
            LocalDate start,
            LocalDate end,
            Map<String, String> fields) {
        if (type == null) fields.put("scheduleType", "周期类型不能为空");
        if (interval < 1 || interval > 120) fields.put("intervalValue", "周期间隔必须在 1 到 120 之间");
        if (type == RecurringScheduleType.MONTHLY && (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31)) {
            fields.put("dayOfMonth", "月度日期必须在 1 到 31 之间");
        }
        if (type == RecurringScheduleType.WEEKLY && dayOfWeek == null) {
            fields.put("dayOfWeek", "周规则必须指定星期");
        }
        if (start == null) fields.put("startOn", "开始日期不能为空");
        if (start != null && end != null && end.isBefore(start)) fields.put("endOn", "结束日期不能早于开始日期");
    }

    private static long parseAmount(String raw, Map<String, String> fields) {
        if (raw == null || raw.trim().isEmpty()) { fields.put("amount", "金额不能为空"); return 0; }
        try {
            long value = Money.parseCents(raw);
            if (value > MAX_AMOUNT_CENTS) fields.put("amount", "金额不能超过 999,999,999.99");
            return value;
        } catch (IllegalArgumentException exception) {
            fields.put("amount", exception.getMessage());
            return 0;
        }
    }

    static LocalDate firstDue(
            RecurringScheduleType type, Integer dayOfMonth, DayOfWeek dayOfWeek, LocalDate start) {
        if (type == RecurringScheduleType.MONTHLY) {
            YearMonth month = YearMonth.from(start);
            LocalDate candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            if (candidate.isBefore(start)) {
                month = month.plusMonths(1);
                candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            }
            return candidate;
        }
        int days = Math.floorMod(dayOfWeek.getValue() - start.getDayOfWeek().getValue(), 7);
        return start.plusDays(days);
    }

    private static LocalDate nextDue(RecurringRule rule, LocalDate current) {
        if (rule.getScheduleType() == RecurringScheduleType.WEEKLY) {
            return current.plusWeeks(rule.getIntervalValue());
        }
        YearMonth target = YearMonth.from(current).plusMonths(rule.getIntervalValue());
        return target.atDay(Math.min(rule.getDayOfMonth(), target.lengthOfMonth()));
    }

    private static LocalDate withinEnd(LocalDate due, LocalDate end) {
        return due == null || end == null || !due.isAfter(end) ? due : null;
    }

    private RecurringRule findRule(long householdId, long ruleId) {
        return rules.findByIdAndHouseholdId(ruleId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("周期规则不存在"));
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static ResourceConflictException staleWrite() {
        return new ResourceConflictException("RECURRING_RULE_CHANGED", "周期规则关联的数据已变化，请刷新后重试");
    }

    private record ValidatedRule(
            TransactionKind kind,
            long amountCents,
            RecurringScheduleType scheduleType,
            int intervalValue,
            Integer dayOfMonth,
            DayOfWeek dayOfWeek,
            LocalDate startOn,
            LocalDate endOn,
            FinancialAccount account,
            FamilyMember member,
            Category category,
            AppUser assignedUser,
            boolean paused) {}
}
