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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BudgetTemplateService {

    private static final int MAX_NOTE_LENGTH = 200;

    private final BudgetTemplateRepository templates;
    private final BudgetTemplateRowRepository rows;
    private final BudgetRepository budgets;
    private final BudgetMonthTotalRepository totals;
    private final CategoryRepository categories;
    private final FamilyMemberRepository members;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public BudgetTemplateService(
            BudgetTemplateRepository templates,
            BudgetTemplateRowRepository rows,
            BudgetRepository budgets,
            BudgetMonthTotalRepository totals,
            CategoryRepository categories,
            FamilyMemberRepository members,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.templates = templates;
        this.rows = rows;
        this.budgets = budgets;
        this.totals = totals;
        this.categories = categories;
        this.members = members;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public List<BudgetTemplateResponse> list(Authentication authentication) {
        long householdId = currentMembership.require(authentication).householdId();
        return templates.findByHouseholdIdOrderByIdDesc(householdId).stream()
                .map(template -> BudgetTemplateResponse.from(template,
                        rows.findByTemplateIdOrderById(template.getId())))
                .toList();
    }

    @Transactional
    public BudgetTemplateResponse create(Authentication authentication, BudgetTemplateCreateRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        String name = request == null || request.name() == null ? ""
                : request.name().trim();
        if (name.isEmpty()) {
            fields.put("name", "模板名称不能为空");
        } else if (name.length() > 100) {
            fields.put("name", "模板名称不能超过 100 个字符");
        }
        List<ValidatedRow> validated = new ArrayList<>();
        List<BudgetTemplateCreateRequest.RowRequest> requested = request == null || request.rows() == null
                ? List.of() : request.rows();
        if (requested.isEmpty()) {
            fields.put("rows", "模板至少需要一条预算");
        } else {
            Set<String> shapes = new HashSet<>();
            for (int index = 0; index < requested.size(); index++) {
                ValidatedRow row = validateRow(householdId, requested.get(index), fields,
                        "rows[" + index + "].");
                if (row == null) continue;
                if (!shapes.add(row.scopeType() + ":" + row.categoryId() + ":" + row.memberId())) {
                    fields.put("rows[" + index + "].scopeType", "同一模板中不允许重复的同范围预算");
                } else {
                    validated.add(row);
                }
            }
        }
        throwIfInvalid(fields);
        if (templates.existsByHouseholdIdAndName(householdId, name)) {
            throw new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的模板名称不能重复");
        }
        try {
            BudgetTemplate template = templates.saveAndFlush(new BudgetTemplate(
                    access.household(), name, access.context().userId(), clock.instant()));
            for (ValidatedRow row : validated) {
                rows.saveAndFlush(new BudgetTemplateRow(householdId, template, row.scopeType(),
                        row.categoryId(), row.memberId(), row.amountCents(), row.note()));
            }
            rows.flush();
            return BudgetTemplateResponse.from(template, rows.findByTemplateIdOrderById(template.getId()));
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RESOURCE_CONFLICT", "模板名称重复或关联数据已变化");
        }
    }

    @Transactional
    public BudgetTemplateApplyResponse apply(Authentication authentication, long templateId, String rawPeriodMonth) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        BudgetTemplate template = templates.findLockedByIdAndHouseholdId(templateId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("预算模板不存在"));
        YearMonth month = BudgetService.requireMonth(rawPeriodMonth);
        List<BudgetTemplateRow> templateRows = rows.findByTemplateIdOrderById(templateId);
        List<BudgetTemplateRow> planned = new ArrayList<>();
        int skipped = 0;
        for (BudgetTemplateRow row : templateRows) {
            if (existsActive(householdId, month, row)) {
                skipped++;
                continue;
            }
            planned.add(row);
        }
        long addedPool = 0;
        for (BudgetTemplateRow row : planned) {
            if (row.getScopeType() == BudgetScopeType.CATEGORY) addedPool += row.getAmountCents();
        }
        Long totalCents = totals.findByHouseholdIdAndPeriodMonth(householdId, month.toString())
                .map(BudgetMonthTotal::getAmountCents).orElse(null);
        if (totalCents != null
                && budgets.sumAllocatedCents(householdId, month.toString(), BudgetScopeType.CATEGORY) + addedPool > totalCents) {
            throw new RequestValidationException(Map.of("periodMonth",
                    "应用后该月的分类预算合计将超过月度总预算 " + Money.formatCents(totalCents)
                            + "，请先调整目标月总预算或缩减模板"));
        }
        int copied = 0;
        for (BudgetTemplateRow row : planned) {
            Category category = row.getCategoryId() == null ? null
                    : categories.findByIdAndHouseholdId(row.getCategoryId(), householdId).orElse(null);
            FamilyMember member = row.getMemberId() == null ? null
                    : members.findByIdAndHouseholdId(row.getMemberId(), householdId).orElse(null);
            if ((row.getCategoryId() != null && category == null)
                    || (row.getMemberId() != null && member == null)) {
                skipped++;
                continue;
            }
            budgets.saveAndFlush(new Budget(access.household(), month, row.getScopeType(),
                    category, member, row.getAmountCents(), row.getNote()));
            copied++;
        }
        budgets.flush();
        return new BudgetTemplateApplyResponse(month.toString(), copied, skipped);
    }

    @Transactional
    public void delete(Authentication authentication, long templateId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        BudgetTemplate template = templates.findLockedByIdAndHouseholdId(templateId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("预算模板不存在"));
        rows.deleteAll(rows.findByTemplateIdOrderById(templateId));
        rows.flush();
        templates.delete(template);
        templates.flush();
    }

    private ValidatedRow validateRow(long householdId, BudgetTemplateCreateRequest.RowRequest request,
            Map<String, String> fields, String prefix) {
        if (request == null) {
            fields.put(prefix + "scopeType", "预算行不能为空");
            return null;
        }
        BudgetScopeType scope = request.scopeType();
        if (scope == null) {
            fields.put(prefix + "scopeType", "预算范围不能为空");
            return null;
        }
        if (scope == BudgetScopeType.TOTAL) {
            fields.put(prefix + "scopeType", "模板不支持家庭总预算，请使用分类预算或成员预算");
            return null;
        }
        Long categoryId = request.categoryId();
        Long memberId = request.memberId();
        Category category = null;
        FamilyMember member = null;
        if (scope == BudgetScopeType.CATEGORY && categoryId == null) {
            fields.put(prefix + "categoryId", "分类预算必须指定支出分类");
            return null;
        }
        if (scope == BudgetScopeType.MEMBER && memberId == null) {
            fields.put(prefix + "memberId", "成员预算必须指定成员");
            return null;
        }
        if (scope == BudgetScopeType.CATEGORY_MEMBER && (categoryId == null || memberId == null)) {
            fields.put(prefix + "categoryId", "分类+成员预算必须同时指定分类与成员");
            return null;
        }
        if (scope != BudgetScopeType.MEMBER) {
            category = categoryId == null ? null
                    : categories.findByIdAndHouseholdId(categoryId, householdId).orElse(null);
            if (category == null || category.getKind() != TransactionKind.EXPENSE) {
                fields.put(prefix + "categoryId", "支出分类不存在");
                return null;
            }
        }
        if (scope != BudgetScopeType.CATEGORY) {
            member = memberId == null ? null
                    : members.findByIdAndHouseholdId(memberId, householdId).orElse(null);
            if (member == null) {
                fields.put(prefix + "memberId", "成员不存在");
                return null;
            }
        }
        Map<String, String> amountFields = new LinkedHashMap<>();
        Long amount = BudgetTotalService.parseAmount(request.amount(), amountFields);
        if (!amountFields.isEmpty()) {
            fields.put(prefix + "amount", amountFields.get("amount"));
            return null;
        }
        String note = request.note() == null ? null : request.note().trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            fields.put(prefix + "note", "备注不能超过 " + MAX_NOTE_LENGTH + " 个字符");
            return null;
        }
        return new ValidatedRow(scope, category == null ? null : category.getId(),
                member == null ? null : member.getId(), amount, note);
    }

    private boolean existsActive(long householdId, YearMonth month, BudgetTemplateRow row) {
        return budgets.existsByHouseholdIdAndPeriodMonthAndScopeTypeAndCategoryIdAndMemberIdAndActiveTrue(
                householdId, month.toString(), row.getScopeType(), row.getCategoryId(), row.getMemberId());
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private record ValidatedRow(BudgetScopeType scopeType, Long categoryId, Long memberId,
            Long amountCents, String note) {
    }
}
