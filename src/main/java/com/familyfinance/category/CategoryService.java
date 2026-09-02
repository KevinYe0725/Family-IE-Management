package com.familyfinance.category;

import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Sort STABLE_SORT = Sort.by(Sort.Direction.ASC, "id");

    private final CategoryRepository categories;
    private final FinancialTransactionRepository transactions;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public CategoryService(
            CategoryRepository categories,
            FinancialTransactionRepository transactions,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.categories = categories;
        this.transactions = transactions;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public CategoryPage list(long householdId, String rawProjection, int page, int size) {
        Projection projection = parseProjection(rawProjection);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, STABLE_SORT);
        var result = projection == Projection.FLAT
                ? categories.findByHouseholdId(householdId, pageRequest)
                : categories.findByHouseholdIdAndParentIsNull(householdId, pageRequest);
        List<CategoryResponse> items = projection == Projection.FLAT
                ? result.getContent().stream().map(CategoryResponse::flat).toList()
                : treeRows(householdId, result.getContent());
        return new CategoryPage(
                items,
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    @Transactional
    public CategoryResponse create(Authentication authentication, CategoryRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        Category parent = resolveParent(householdId, request.parentId(), request.kind(), null, false);
        validateUnique(householdId, request.kind(), name, null);
        try {
            Category category = categories.saveAndFlush(new Category(
                    access.household(), request.kind(), name, color, false, parent, clock.instant()));
            return CategoryResponse.flat(category);
        } catch (DataIntegrityViolationException exception) {
            throw translateWriteConflict(exception);
        }
    }

    @Transactional
    public CategoryResponse update(Authentication authentication, long categoryId, CategoryRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Category category = findOne(householdId, categoryId);
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        boolean hasChildren = categories.existsByHouseholdIdAndParentId(householdId, categoryId);
        Category parent = resolveParent(householdId, request.parentId(), request.kind(), category, hasChildren);

        if (hasChildren && category.getKind() != request.kind()) {
            throw hierarchyError("kind", "父分类的收支类型必须和所有子分类一致");
        }
        if (transactions.existsByHouseholdIdAndCategoryId(householdId, categoryId)
                && category.getKind() != request.kind()) {
            throw resourceInUse("该分类已被收支记录使用，无法修改收支类型");
        }
        if (categories.countBudgetReferences(householdId, categoryId) > 0
                && category.getKind() != request.kind()) {
            throw resourceInUse("该分类已被预算或预算修订历史使用，无法修改收支类型");
        }
        if (categories.countRecurringReferences(householdId, categoryId) > 0
                && category.getKind() != request.kind()) {
            throw resourceInUse("该分类已被周期规则使用，无法修改收支类型");
        }
        validateUnique(householdId, request.kind(), name, categoryId);
        try {
            category.update(request.kind(), name, color, parent);
            categories.flush();
            return CategoryResponse.flat(category);
        } catch (IllegalArgumentException exception) {
            throw hierarchyError("parentId", exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            throw translateWriteConflict(exception);
        }
    }

    @Transactional
    public void delete(Authentication authentication, long categoryId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        Category category = findOne(householdId, categoryId);
        if (categories.existsByHouseholdIdAndParentId(householdId, categoryId)) {
            throw resourceInUse("该分类仍有子分类，无法删除");
        }
        if (transactions.existsByHouseholdIdAndCategoryId(householdId, categoryId)) {
            throw resourceInUse("该分类已被收支记录使用，无法删除");
        }
        if (categories.countBudgetReferences(householdId, categoryId) > 0) {
            throw resourceInUse("该分类已被预算或预算历史使用，无法删除");
        }
        if (categories.countRecurringReferences(householdId, categoryId) > 0) {
            throw resourceInUse("该分类已被周期规则使用，无法删除");
        }
        try {
            categories.delete(category);
            categories.flush();
        } catch (DataIntegrityViolationException exception) {
            throw resourceInUse("该分类仍被历史数据使用，无法删除");
        }
    }

    private List<CategoryResponse> treeRows(long householdId, List<Category> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        List<Long> rootIds = roots.stream().map(Category::getId).toList();
        Map<Long, List<Category>> childrenByParent = categories
                .findByHouseholdIdAndParentIdInOrderByIdAsc(householdId, rootIds)
                .stream()
                .collect(Collectors.groupingBy(
                        child -> child.getParent().getId(),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        return roots.stream()
                .map(root -> CategoryResponse.tree(
                        root,
                        childrenByParent.getOrDefault(root.getId(), List.of()).stream()
                                .map(CategoryResponse::flat)
                                .toList()))
                .toList();
    }

    private Category resolveParent(
            long householdId,
            Long parentId,
            TransactionKind kind,
            Category category,
            boolean categoryHasChildren) {
        if (parentId == null) {
            return null;
        }
        if (category != null && category.getId().equals(parentId)) {
            throw hierarchyError("parentId", "分类不能成为自己的父分类");
        }
        Category parent = categories.findByIdAndHouseholdId(parentId, householdId)
                .orElseThrow(() -> hierarchyError("parentId", "父分类不存在"));
        if (parent.getKind() != kind) {
            throw hierarchyError("parentId", "父子分类的收支类型必须一致");
        }
        if (parent.getParent() != null) {
            throw hierarchyError("parentId", "分类最多只允许两级");
        }
        if (categoryHasChildren) {
            throw hierarchyError("parentId", "已有子分类的分类不能移动到另一分类下");
        }
        return parent;
    }

    private Category findOne(long householdId, long categoryId) {
        return categories.findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("分类不存在"));
    }

    private void validateUnique(long householdId, TransactionKind kind, String name, Long categoryId) {
        boolean exists = categoryId == null
                ? categories.existsByHouseholdIdAndKindAndName(householdId, kind, name)
                : categories.existsByHouseholdIdAndKindAndNameAndIdNot(householdId, kind, name, categoryId);
        if (exists) {
            throw duplicateName();
        }
    }

    private static String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw validationError("name", "分类名称不能为空");
        }
        if (name.length() > 30) {
            throw validationError("name", "分类名称长度不能超过 30 个字符");
        }
        return name;
    }

    private static String normalizeColor(String rawColor) {
        String color = rawColor == null ? "" : rawColor.trim();
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            throw validationError("color", "分类颜色必须是 #RRGGBB 格式");
        }
        return color;
    }

    private static Projection parseProjection(String rawProjection) {
        String projection = rawProjection == null ? "flat" : rawProjection.trim().toLowerCase(Locale.ROOT);
        return switch (projection) {
            case "flat" -> Projection.FLAT;
            case "tree" -> Projection.TREE;
            default -> throw validationError("projection", "分类投影只能是 flat 或 tree");
        };
    }

    private static RuntimeException translateWriteConflict(DataIntegrityViolationException exception) {
        String details = exceptionMessages(exception).toLowerCase(Locale.ROOT);
        if (details.contains("uk_categories_household_kind_name")) {
            return duplicateName();
        }
        if (details.contains("fk_categories_parent_household_kind")
                || details.contains("ck_categories_not_self_parent")) {
            return hierarchyError("parentId", "分类层级已变化，请刷新后重试");
        }
        return new ResourceConflictException("CATEGORY_CHANGED", "分类数据已变化，请刷新后重试");
    }

    private static String exceptionMessages(Throwable exception) {
        StringBuilder result = new StringBuilder();
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null) {
                result.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static RequestValidationException validationError(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new RequestValidationException(fields);
    }

    private static CategoryHierarchyException hierarchyError(String field, String message) {
        return new CategoryHierarchyException(field, message);
    }

    private static ResourceConflictException duplicateName() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一收支类型下的分类名称不能重复");
    }

    private static ResourceConflictException resourceInUse(String message) {
        return new ResourceConflictException("RESOURCE_IN_USE", message);
    }

    private enum Projection {
        FLAT,
        TREE
    }
}
