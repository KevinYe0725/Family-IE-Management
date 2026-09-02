package com.familyfinance.transaction;

import java.math.BigDecimal;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.shared.RequestValidationException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class TransactionFilterParser {

    private final FamilyMemberRepository memberRepository;
    private final CategoryRepository categoryRepository;

    TransactionFilterParser(FamilyMemberRepository memberRepository, CategoryRepository categoryRepository) {
        this.memberRepository = memberRepository;
        this.categoryRepository = categoryRepository;
    }

    TransactionCriteria parse(long householdId, TransactionFilter filter) {
        Map<String, String> fields = new LinkedHashMap<>();
        LocalDate from = parseOptionalDate(filter.from(), "from", fields);
        LocalDate to = parseOptionalDate(filter.to(), "to", fields);
        if (filter.month() != null && !filter.month().isBlank()) {
            try {
                YearMonth month = YearMonth.parse(filter.month().trim());
                from = month.atDay(1);
                to = month.atEndOfMonth();
            } catch (DateTimeParseException exception) {
                fields.put("month", "月份格式必须是 YYYY-MM");
            }
        }
        if (from != null && to != null && from.isAfter(to)) {
            fields.put("to", "结束日期不能早于开始日期");
        }

        TransactionKind kind = parseKind(filter.kind(), fields);
        if (filter.memberId() != null) {
            requireMemberExists(householdId, filter.memberId(), fields);
        }
        if (filter.categoryId() != null) {
            requireCategoryExists(householdId, filter.categoryId(), kind, fields);
        }
        String keyword = normalizeText(filter.q());
        if (keyword != null && keyword.length() > 100) {
            fields.put("q", "关键字长度不能超过 100 个字符");
        }
        throwIfInvalid(fields);
        return new TransactionCriteria(householdId, from, to, kind, filter.memberId(), filter.categoryId(), keyword,
            filter.minAmount() != null ? filter.minAmount().multiply(new BigDecimal("100")) : null,
            filter.maxAmount() != null ? filter.maxAmount().multiply(new BigDecimal("100")) : null
        );
    }

    private void requireMemberExists(long householdId, long memberId, Map<String, String> fields) {
        if (memberRepository.findByIdAndHouseholdId(memberId, householdId).isEmpty()) {
            fields.put("memberId", "成员不存在");
        }
    }

    private void requireCategoryExists(
            long householdId,
            long categoryId,
            TransactionKind kind,
            Map<String, String> fields) {
        categoryRepository.findByIdAndHouseholdId(categoryId, householdId)
                .ifPresentOrElse(category -> {
                    if (kind != null && category.getKind() != kind) {
                        fields.put("categoryId", "分类类型必须和收支类型一致");
                    }
                }, () -> fields.put("categoryId", "分类不存在"));
    }

    private static LocalDate parseOptionalDate(String rawDate, String field, Map<String, String> fields) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (DateTimeParseException exception) {
            fields.put(field, "日期格式必须是 YYYY-MM-DD");
            return null;
        }
    }

    private static TransactionKind parseKind(String rawKind, Map<String, String> fields) {
        if (rawKind == null || rawKind.isBlank()) {
            return null;
        }
        try {
            return TransactionKind.fromJson(rawKind.trim());
        } catch (IllegalArgumentException exception) {
            fields.put("kind", exception.getMessage());
            return null;
        }
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }
}
