package com.familyfinance.transaction;

import com.familyfinance.category.Category;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public final class TransactionTestFixtures {

    private TransactionTestFixtures() {
    }

    public static FinancialTransaction newTransaction(
            FinancialAccountRepository accounts,
            AppUserRepository users,
            Household household,
            FamilyMember member,
            Category category,
            TransactionKind kind,
            Long amountCents,
            LocalDate occurredOn,
            String merchant,
            String location,
            String note,
            Instant createdAt,
            Instant updatedAt) {
        FinancialAccount account = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(household.getId())
                .orElseGet(() -> accounts.save(new FinancialAccount(
                        household,
                        FinancialAccount.DEFAULT_NAME,
                        AccountType.CASH,
                        FinancialAccount.STAGE_TWO_CURRENCY,
                        0L)));
        AppUser creator = users.findAll().stream()
                .filter(user -> sameHousehold(household, user.getHousehold()))
                .findFirst()
                .orElseGet(() -> users.save(new AppUser(
                        household,
                        "fixture-" + household.getId() + "@local.family",
                        "fixture-" + household.getId() + "@local.family",
                        "测试用户",
                        "encoded-password",
                        createdAt)));
        return new FinancialTransaction(
                household,
                account,
                creator,
                member,
                category,
                kind,
                amountCents,
                occurredOn,
                merchant,
                location,
                note,
                createdAt,
                updatedAt);
    }

    private static boolean sameHousehold(Household expected, Household actual) {
        return expected == actual
                || (expected.getId() != null && Objects.equals(expected.getId(), actual.getId()));
    }
}
