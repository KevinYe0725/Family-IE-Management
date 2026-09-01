package com.familyfinance.category;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final HouseholdRepository householdRepository;
    private final CategoryRepository categoryRepository;
    private final FinancialTransactionRepository transactionRepository;

    public CategoryService(
            HouseholdRepository householdRepository,
            CategoryRepository categoryRepository,
            FinancialTransactionRepository transactionRepository) {
        this.householdRepository = householdRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<CategoryResponse> list(long householdId) {
        return categoryRepository.findByHouseholdIdOrderById(householdId).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse create(long householdId, CategoryRequest request) {
        Household household = householdRepository.findById(householdId)
                .orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        validateUnique(householdId, request.kind(), name, null);
        try {
            Category category = categoryRepository.save(new Category(
                    household,
                    request.kind(),
                    name,
                    color,
                    false,
                    Instant.now()));
            return CategoryResponse.from(category);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public CategoryResponse update(long householdId, long categoryId, CategoryRequest request) {
        Category category = categoryRepository.findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("分类不存在"));
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        boolean referenced = transactionRepository.existsByHouseholdIdAndCategoryId(householdId, categoryId);
        if (referenced && category.getKind() != request.kind()) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "该分类已被收支记录使用，无法修改收支类型");
        }
        validateUnique(householdId, request.kind(), name, categoryId);
        try {
            category.update(request.kind(), name, color);
            return CategoryResponse.from(category);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public void delete(long householdId, long categoryId) {
        Category category = categoryRepository.findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("分类不存在"));
        if (transactionRepository.existsByHouseholdIdAndCategoryId(householdId, categoryId)) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "该分类已被收支记录使用，无法删除");
        }
        categoryRepository.delete(category);
    }

    private void validateUnique(long householdId, TransactionKind kind, String name, Long categoryId) {
        boolean exists = categoryId == null
                ? categoryRepository.existsByHouseholdIdAndKindAndName(householdId, kind, name)
                : categoryRepository.existsByHouseholdIdAndKindAndNameAndIdNot(householdId, kind, name, categoryId);
        if (exists) {
            throw duplicateName();
        }
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw validationError("name", "分类名称不能为空");
        }
        if (name.length() > 30) {
            throw validationError("name", "分类名称长度不能超过 30 个字符");
        }
        return name;
    }

    private String normalizeColor(String rawColor) {
        String color = rawColor == null ? "" : rawColor.trim();
        if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
            throw validationError("color", "分类颜色必须是 #RRGGBB 格式");
        }
        return color;
    }

    private RequestValidationException validationError(String field, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(field, message);
        return new RequestValidationException(fields);
    }

    private ResourceConflictException duplicateName() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一收支类型下的分类名称不能重复");
    }
}
