package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=false")
@Transactional
class DashboardServiceTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    DashboardService dashboardService;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Autowired
    FinancialAccountRepository accountRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Test
    void monthlyDashboardAggregatesHouseholdTransactionsWithStableOrdering() {
        Household household = householdRepository.save(new Household("测试家庭", TEST_TIME));
        FamilyMember kevin = memberRepository.save(new FamilyMember(household, "Kevin", "爸爸", TEST_TIME));
        FamilyMember lily = memberRepository.save(new FamilyMember(household, "Lily", "妈妈", TEST_TIME));
        Category salary = categoryRepository.save(new Category(
                household, TransactionKind.INCOME, "工资", "#3B7A72", true, TEST_TIME));
        Category food = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, TEST_TIME));
        Category transport = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "交通", "#17324D", true, TEST_TIME));

        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                kevin,
                salary,
                TransactionKind.INCOME,
                500000L,
                LocalDate.parse("2026-09-05"),
                "公司",
                "杭州",
                "工资",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                lily,
                food,
                TransactionKind.EXPENSE,
                12000L,
                LocalDate.parse("2026-09-10"),
                "菜场",
                "杭州",
                "餐饮",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                kevin,
                transport,
                TransactionKind.EXPENSE,
                8000L,
                LocalDate.parse("2026-09-07"),
                "地铁",
                "杭州",
                "交通",
                TEST_TIME,
                TEST_TIME));

        DashboardResponse dashboard = dashboardService.dashboard(household.getId(), YearMonth.parse("2026-09"));

        assertThat(dashboard.summary().income()).isEqualTo("5000.00");
        assertThat(dashboard.summary().expense()).isEqualTo("200.00");
        assertThat(dashboard.summary().balance()).isEqualTo("4800.00");
        assertThat(dashboard.daily())
                .extracting(DailyTrendResponse::date, DailyTrendResponse::income, DailyTrendResponse::expense)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-09-05", "5000.00", "0.00"),
                        org.assertj.core.groups.Tuple.tuple("2026-09-07", "0.00", "80.00"),
                        org.assertj.core.groups.Tuple.tuple("2026-09-10", "0.00", "120.00"));
        assertThat(dashboard.expenseByCategory())
                .extracting(
                        ExpenseCategoryResponse::categoryName,
                        ExpenseCategoryResponse::amount,
                        ExpenseCategoryResponse::sharePercent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("餐饮", "120.00", "60.0"),
                        org.assertj.core.groups.Tuple.tuple("交通", "80.00", "40.0"));
        assertThat(dashboard.expenseByMember())
                .extracting(MemberExpenseResponse::memberName, MemberExpenseResponse::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Lily", "120.00"),
                        org.assertj.core.groups.Tuple.tuple("Kevin", "80.00"));
    }
}
