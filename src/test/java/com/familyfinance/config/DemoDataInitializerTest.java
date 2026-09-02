package com.familyfinance.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
class DemoDataInitializerTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    HouseholdRepository households;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    CategoryRepository categories;

    @Autowired
    FinancialTransactionRepository transactions;

    @Test
    void seedIsIdempotentAndStoresOnlyEncodedPassword() {
        assertThat(users.count()).isEqualTo(1);
        assertThat(households.count()).isEqualTo(1);
        assertThat(members.count()).isEqualTo(5);
        assertThat(categories.count()).isGreaterThanOrEqualTo(8);
        assertThat(transactions.count()).isGreaterThanOrEqualTo(12);
        AppUser demo = users.findByUsername("demo").orElseThrow();
        assertThat(demo.getEmail()).isEqualTo("demo@local.family");
        assertThat(demo.getPasswordHash()).startsWith("$2");
        assertThat(demo.getPasswordHash()).doesNotContain("demo1234");
    }
}
