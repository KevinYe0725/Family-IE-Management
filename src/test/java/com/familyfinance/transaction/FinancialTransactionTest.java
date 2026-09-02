package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import com.familyfinance.household.AppUser;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialTransactionTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final LocalDate OCCURRED_ON = LocalDate.parse("2026-09-01");

    @Test
    void rejectsMemberFromAnotherHouseholdBeforePersistence() {
        Household household = new Household("主家庭", CREATED_AT);
        Household otherHousehold = new Household("其他家庭", CREATED_AT);
        FamilyMember member = new FamilyMember(otherHousehold, "成员", "成员", CREATED_AT);
        Category category = new Category(household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, CREATED_AT);

        assertThatThrownBy(() -> transaction(household, member, category, TransactionKind.EXPENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction member must belong to the transaction household");
    }

    @Test
    void rejectsCategoryFromAnotherHouseholdBeforePersistence() {
        Household household = new Household("主家庭", CREATED_AT);
        Household otherHousehold = new Household("其他家庭", CREATED_AT);
        FamilyMember member = new FamilyMember(household, "成员", "成员", CREATED_AT);
        Category category = new Category(otherHousehold, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, CREATED_AT);

        assertThatThrownBy(() -> transaction(household, member, category, TransactionKind.EXPENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction category must belong to the transaction household");
    }

    @Test
    void rejectsCategoryKindMismatchBeforePersistence() {
        Household household = new Household("主家庭", CREATED_AT);
        FamilyMember member = new FamilyMember(household, "成员", "成员", CREATED_AT);
        Category category = new Category(household, TransactionKind.INCOME, "工资", "#3B7A72", true, CREATED_AT);

        assertThatThrownBy(() -> transaction(household, member, category, TransactionKind.EXPENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction category kind must match transaction kind");
    }

    @Test
    void rejectsAccountFromAnotherHouseholdBeforePersistence() {
        Household household = new Household("主家庭", CREATED_AT);
        Household otherHousehold = new Household("其他家庭", CREATED_AT);
        FamilyMember member = new FamilyMember(household, "成员", "成员", CREATED_AT);
        Category category = new Category(household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, CREATED_AT);
        FinancialAccount account = account(otherHousehold);
        AppUser creator = user(household);

        assertThatThrownBy(() -> transaction(household, account, creator, member, category, TransactionKind.EXPENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction account must belong to the transaction household");
    }

    @Test
    void rejectsCreatorFromAnotherHouseholdBeforePersistence() {
        Household household = new Household("主家庭", CREATED_AT);
        Household otherHousehold = new Household("其他家庭", CREATED_AT);
        FamilyMember member = new FamilyMember(household, "成员", "成员", CREATED_AT);
        Category category = new Category(household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, CREATED_AT);

        assertThatThrownBy(() -> transaction(
                        household,
                        account(household),
                        user(otherHousehold),
                        member,
                        category,
                        TransactionKind.EXPENSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction creator must belong to the transaction household");
    }

    private static FinancialTransaction transaction(
            Household household,
            FamilyMember member,
        Category category,
        TransactionKind kind) {
        return transaction(household, account(household), user(household), member, category, kind);
    }

    private static FinancialTransaction transaction(
            Household household,
            FinancialAccount account,
            AppUser creator,
            FamilyMember member,
            Category category,
            TransactionKind kind) {
        return new FinancialTransaction(
                household,
                account,
                creator,
                member,
                category,
                kind,
                100L,
                OCCURRED_ON,
                "商家",
                "杭州",
                "备注",
                CREATED_AT,
                CREATED_AT);
    }

    private static FinancialAccount account(Household household) {
        return new FinancialAccount(household, "默认账户", AccountType.CASH, "CNY", 0L);
    }

    private static AppUser user(Household household) {
        return new AppUser(
                household,
                "owner@local.family",
                "owner@local.family",
                "所有者",
                "encoded-password",
                CREATED_AT);
    }
}
