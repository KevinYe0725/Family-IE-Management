package com.familyfinance.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.util.List;
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

    @Autowired
    FinancialAccountRepository accounts;

    @Autowired
    DemoDataInitializer initializer;

    @Test
    void seedIsIdempotentLinksOnlyTheFirstMemberAndStoresOnlyEncodedPassword() {
        initializer.run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(households.count()).isEqualTo(1);
        assertThat(members.count()).isEqualTo(5);
        assertThat(categories.count()).isEqualTo(8);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(transactions.count()).isEqualTo(12);
        AppUser demo = users.findByUsername("demo").orElseThrow();
        assertThat(demo.getEmail()).isEqualTo("demo@local.family");
        assertThat(demo.getPasswordHash()).startsWith("$2");
        assertThat(demo.getPasswordHash()).doesNotContain("demo1234");
        List<FamilyMember> seededMembers = members.findByHouseholdOrderById(demo.getHousehold());
        assertThat(seededMembers).filteredOn(member -> member.getLinkedUser() != null).singleElement()
                .satisfies(member -> {
                    assertThat(member.getId()).isEqualTo(seededMembers.get(0).getId());
                    assertThat(member.getName()).isEqualTo("Kevin");
                    assertThat(member.getLinkedUser().getId()).isEqualTo(demo.getId());
                });
        assertThat(transactions.findAll())
                .allSatisfy(transaction -> {
                    assertThat(transaction.getAccount().getId()).isEqualTo(accounts.findAll().get(0).getId());
                    assertThat(transaction.getCreatedByUser().getId()).isEqualTo(demo.getId());
                    assertThat(transaction.getSourceType().name()).isEqualTo("MANUAL");
                    assertThat(transaction.getSourceId()).isNull();
                });
    }
}
