package com.familyfinance.identity;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.Household;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class RegistrationDefaults {

    private final CategoryRepository categories;

    RegistrationDefaults(CategoryRepository categories) {
        this.categories = categories;
    }

    void createFor(Household household, Instant createdAt) {
        categories.saveAll(List.of(
                new Category(household, TransactionKind.INCOME, "工资", "#3B7A72", true, createdAt),
                new Category(household, TransactionKind.INCOME, "奖金", "#C49A4A", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "交通", "#17324D", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "购物", "#C49A4A", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "教育", "#3B7A72", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "医疗", "#7A4A3B", true, createdAt),
                new Category(household, TransactionKind.EXPENSE, "居家", "#4B6680", true, createdAt)));
    }
}
