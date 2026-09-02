package com.familyfinance.budget;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class BudgetInvariantTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void aggregateRejectsInvalidScopeShapeExpenseKindHouseholdAndAmount() {
        Household household = new Household("当前家庭", NOW);
        Household foreign = new Household("外部家庭", NOW);
        Category expense = new Category(household, TransactionKind.EXPENSE, "餐饮", "#123456", false, NOW);
        Category income = new Category(household, TransactionKind.INCOME, "工资", "#654321", false, NOW);
        FamilyMember foreignMember = new FamilyMember(foreign, "外部成员", "成员", NOW);

        assertThatThrownBy(() -> new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.TOTAL, expense, null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.CATEGORY, income, null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.MEMBER, null, foreignMember, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.TOTAL, null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.TOTAL, null, null, 100_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
