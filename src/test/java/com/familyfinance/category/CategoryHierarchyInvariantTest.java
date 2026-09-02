package com.familyfinance.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyfinance.household.Household;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CategoryHierarchyInvariantTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void acceptsOneSameHouseholdParentWithMatchingKind() {
        Household household = new Household("家庭", CREATED_AT);
        Category parent = category(household, TransactionKind.EXPENSE, "购物", null);

        Category child = category(household, TransactionKind.EXPENSE, "服饰", parent);

        assertThat(child.getParent()).isSameAs(parent);
    }

    @Test
    void rejectsGrandparentKindMismatchAndCrossHouseholdParent() {
        Household household = new Household("家庭", CREATED_AT);
        Household otherHousehold = new Household("其他家庭", CREATED_AT);
        Category expenseParent = category(household, TransactionKind.EXPENSE, "购物", null);
        Category child = category(household, TransactionKind.EXPENSE, "服饰", expenseParent);
        Category incomeParent = category(household, TransactionKind.INCOME, "工资", null);
        Category outsiderParent = category(otherHousehold, TransactionKind.EXPENSE, "外部", null);

        assertThatThrownBy(() -> category(household, TransactionKind.EXPENSE, "外套", child))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category hierarchy supports at most two levels");
        assertThatThrownBy(() -> category(household, TransactionKind.EXPENSE, "错误类型", incomeParent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parent category kind must match child category kind");
        assertThatThrownBy(() -> category(household, TransactionKind.EXPENSE, "错误家庭", outsiderParent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parent category must belong to the child category household");
    }

    @Test
    void updateCannotMakeAnExistingCategoryInvalid() {
        Household household = new Household("家庭", CREATED_AT);
        Category parent = category(household, TransactionKind.EXPENSE, "购物", null);
        Category child = category(household, TransactionKind.EXPENSE, "服饰", parent);

        assertThatThrownBy(() -> child.update(TransactionKind.INCOME, "错误类型", "#445566", parent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parent category kind must match child category kind");
    }

    @Test
    void rejectsSelfParentByObjectIdentityAndPersistedId() {
        Household household = new Household("家庭", CREATED_AT);
        Category category = category(household, TransactionKind.EXPENSE, "购物", null);

        assertThatThrownBy(() -> category.update(TransactionKind.EXPENSE, "购物", "#445566", category))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category cannot be its own parent");

        Category samePersistedCategory = category(household, TransactionKind.EXPENSE, "购物副本", null);
        ReflectionTestUtils.setField(category, "id", 42L);
        ReflectionTestUtils.setField(samePersistedCategory, "id", 42L);
        assertThatThrownBy(() -> category.update(
                        TransactionKind.EXPENSE, "购物", "#445566", samePersistedCategory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category cannot be its own parent");
    }

    private static Category category(
            Household household, TransactionKind kind, String name, Category parent) {
        return new Category(household, kind, name, "#112233", false, parent, CREATED_AT);
    }
}
