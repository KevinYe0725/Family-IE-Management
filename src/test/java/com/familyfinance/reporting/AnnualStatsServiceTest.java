package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=false")
@Transactional
class AnnualStatsServiceTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    AnnualStatsService annualStatsService;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Test
    void annualStatsReturnsFullYearData() {
        Fixture fixture = fixture();
        saveIncome(fixture, 50000L, "2026-01-15");
        saveIncome(fixture, 50000L, "2026-02-15");
        saveIncome(fixture, 50000L, "2026-03-15");
        saveIncome(fixture, 50000L, "2026-04-15");
        saveIncome(fixture, 50000L, "2026-05-15");
        saveIncome(fixture, 50000L, "2026-06-15");
        saveIncome(fixture, 50000L, "2026-07-15");
        saveIncome(fixture, 50000L, "2026-08-15");
        saveIncome(fixture, 50000L, "2026-09-15");
        saveIncome(fixture, 50000L, "2026-10-15");
        saveIncome(fixture, 50000L, "2026-11-15");
        saveIncome(fixture, 50000L, "2026-12-15");

        AnnualStatsResponse response = annualStatsService.annualStats(fixture.household().getId(), 2026);

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.monthlyCashFlows()).hasSize(12);
        assertThat(response.summary().totalIncome()).isEqualTo("6000.00");
        assertThat(response.summary().totalExpense()).isEqualTo("0.00");
        assertThat(response.summary().totalBalance()).isEqualTo("6000.00");
        assertThat(response.summary().monthlyAverageIncome()).isEqualTo("500.00");
        assertThat(response.summary().monthlyAverageExpense()).isEqualTo("0.00");
        assertThat(response.summary().monthlyAverageBalance()).isEqualTo("500.00");
    }

    @Test
    void annualStatsComputesCorrectAverages() {
        Fixture fixture = fixture();
        saveIncome(fixture, 50000L, "2026-01-15");
        saveExpense(fixture, 10000L, "2026-01-20");
        saveIncome(fixture, 50000L, "2026-02-15");
        saveExpense(fixture, 20000L, "2026-02-20");
        saveIncome(fixture, 50000L, "2026-03-15");
        saveExpense(fixture, 30000L, "2026-03-20");

        AnnualStatsResponse response = annualStatsService.annualStats(fixture.household().getId(), 2026);

        assertThat(response.summary().totalIncome()).isEqualTo("1500.00");
        assertThat(response.summary().totalExpense()).isEqualTo("600.00");
        assertThat(response.summary().totalBalance()).isEqualTo("900.00");
        assertThat(response.summary().monthlyAverageIncome()).isEqualTo("125.00");
        assertThat(response.summary().monthlyAverageExpense()).isEqualTo("50.00");
        assertThat(response.summary().monthlyAverageBalance()).isEqualTo("75.00");
    }

    @Test
    void annualStatsComputesCorrectDeviationPercent() {
        Fixture fixture = fixture();
        saveExpense(fixture, 10000L, "2026-01-15");
        saveExpense(fixture, 20000L, "2026-02-15");
        saveExpense(fixture, 30000L, "2026-03-15");

        AnnualStatsResponse response = annualStatsService.annualStats(fixture.household().getId(), 2026);

        assertThat(response.monthlyCashFlows().get(0).month()).isEqualTo(1);
        assertThat(response.monthlyCashFlows().get(0).vsAveragePercent()).isEqualTo("100.0");
        assertThat(response.monthlyCashFlows().get(1).month()).isEqualTo(2);
        assertThat(response.monthlyCashFlows().get(1).vsAveragePercent()).isEqualTo("300.0");
        assertThat(response.monthlyCashFlows().get(2).month()).isEqualTo(3);
        assertThat(response.monthlyCashFlows().get(2).vsAveragePercent()).isEqualTo("500.0");
    }

    @Test
    void annualStatsHandlesEmptyYear() {
        Fixture fixture = fixture();

        AnnualStatsResponse response = annualStatsService.annualStats(fixture.household().getId(), 2026);

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.monthlyCashFlows()).hasSize(12);
        assertThat(response.summary().totalIncome()).isEqualTo("0.00");
        assertThat(response.summary().totalExpense()).isEqualTo("0.00");
        assertThat(response.summary().totalBalance()).isEqualTo("0.00");
        assertThat(response.summary().monthlyAverageIncome()).isEqualTo("0.00");
        assertThat(response.summary().monthlyAverageExpense()).isEqualTo("0.00");
        assertThat(response.summary().monthlyAverageBalance()).isEqualTo("0.00");
        for (MonthlyCashFlow cashFlow : response.monthlyCashFlows()) {
            assertThat(cashFlow.income()).isEqualTo("0.00");
            assertThat(cashFlow.expense()).isEqualTo("0.00");
            assertThat(cashFlow.balance()).isEqualTo("0.00");
            assertThat(cashFlow.vsAveragePercent()).isEqualTo("0.0");
        }
    }

    @Test
    void annualStatsSplitsIncomeAndExpense() {
        Fixture fixture = fixture();
        saveIncome(fixture, 50000L, "2026-01-15");
        saveExpense(fixture, 10000L, "2026-01-20");

        AnnualStatsResponse response = annualStatsService.annualStats(fixture.household().getId(), 2026);

        MonthlyCashFlow january = response.monthlyCashFlows().get(0);
        assertThat(january.month()).isEqualTo(1);
        assertThat(january.income()).isEqualTo("500.00");
        assertThat(january.expense()).isEqualTo("100.00");
        assertThat(january.balance()).isEqualTo("400.00");
    }

    private Fixture fixture() {
        Household household = householdRepository.save(new Household("年度统计测试家庭", TEST_TIME));
        FamilyMember kevin = memberRepository.save(new FamilyMember(household, "Kevin", "爸爸", TEST_TIME));
        Category salary = categoryRepository.save(new Category(
                household, TransactionKind.INCOME, "工资", "#3B7A72", true, TEST_TIME));
        Category food = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, TEST_TIME));
        return new Fixture(household, kevin, salary, food);
    }

    private void saveIncome(Fixture fixture, Long amountCents, String occurredOn) {
        transactionRepository.save(new FinancialTransaction(
                fixture.household(),
                fixture.member(),
                fixture.salary(),
                TransactionKind.INCOME,
                amountCents,
                LocalDate.parse(occurredOn),
                "公司",
                "杭州",
                "工资",
                TEST_TIME,
                TEST_TIME));
    }

    private void saveExpense(Fixture fixture, Long amountCents, String occurredOn) {
        transactionRepository.save(new FinancialTransaction(
                fixture.household(),
                fixture.member(),
                fixture.food(),
                TransactionKind.EXPENSE,
                amountCents,
                LocalDate.parse(occurredOn),
                "菜场",
                "杭州",
                "餐饮",
                TEST_TIME,
                TEST_TIME));
    }

    private record Fixture(Household household, FamilyMember member, Category salary, Category food) {
    }
}
