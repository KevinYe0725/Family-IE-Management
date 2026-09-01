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
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=false")
@Transactional
class AnalysisServiceTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    AnalysisService analysisService;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Test
    void analysisReturnsOrderedRuleBasedInsightsFromCurrentAndHistoricalExpenses() {
        Fixture fixture = fixture();
        saveExpense(fixture, 100000L, "2026-06-03", fixture.food(), "六月家庭餐饮");
        saveExpense(fixture, 90000L, "2026-07-04", fixture.food(), "七月家庭餐饮");
        saveExpense(fixture, 110000L, "2026-08-05", fixture.food(), "八月家庭餐饮");
        saveExpense(fixture, 90000L, "2026-09-08", fixture.food(), "九月家庭餐饮");
        saveExpense(fixture, 60000L, "2026-09-11", fixture.transport(), "九月交通");

        AnalysisResponse analysis = analysisService.analysis(fixture.household().getId(), YearMonth.parse("2026-09"));

        assertThat(analysis.historyStatus()).isEqualTo("sufficient");
        assertThat(analysis.insights())
                .extracting(InsightResponse::type, InsightResponse::metric)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MONTHLY_INCREASE", "50.0%"),
                        org.assertj.core.groups.Tuple.tuple("TOP_CATEGORY", "60.0%"),
                        org.assertj.core.groups.Tuple.tuple("LARGEST_EXPENSE", "900.00"));
        assertThat(analysis.insights().get(1).message()).contains("餐饮");
        assertThat(analysis.insights().get(2).message()).contains("九月家庭餐饮");
    }

    @Test
    void analysisMarksInsufficientHistoryWithoutMonthlyComparison() {
        Fixture fixture = fixture();
        saveExpense(fixture, 100000L, "2026-08-03", fixture.food(), "八月家庭餐饮");
        saveExpense(fixture, 150000L, "2026-09-09", fixture.food(), "九月家庭餐饮");

        AnalysisResponse analysis = analysisService.analysis(fixture.household().getId(), YearMonth.parse("2026-09"));

        assertThat(analysis.historyStatus()).isEqualTo("insufficient");
        assertThat(analysis.insights())
                .extracting(InsightResponse::type)
                .doesNotContain("MONTHLY_INCREASE");
    }

    @Test
    void analysisTreatsEqualCurrentExpenseAndHistoryAverageAsStable() {
        Fixture fixture = fixture();
        saveExpense(fixture, 100000L, "2026-07-03", fixture.food(), "七月家庭餐饮");
        saveExpense(fixture, 100000L, "2026-08-03", fixture.food(), "八月家庭餐饮");
        saveExpense(fixture, 100000L, "2026-09-09", fixture.food(), "九月家庭餐饮");

        AnalysisResponse analysis = analysisService.analysis(fixture.household().getId(), YearMonth.parse("2026-09"));

        assertThat(analysis.historyStatus()).isEqualTo("sufficient");
        assertThat(analysis.insights().get(0).type()).isEqualTo("MONTHLY_STABLE");
        assertThat(analysis.insights().get(0).title()).isEqualTo("本月支出与近期平均持平");
        assertThat(analysis.insights().get(0).metric()).isEqualTo("0.0%");
        assertThat(analysis.insights()).hasSize(3);
    }

    @Test
    void analysisDoesNotFabricateInsightsForEmptyCurrentMonth() {
        Fixture fixture = fixture();
        saveExpense(fixture, 100000L, "2026-06-03", fixture.food(), "六月家庭餐饮");
        saveExpense(fixture, 90000L, "2026-07-04", fixture.food(), "七月家庭餐饮");
        saveExpense(fixture, 110000L, "2026-08-05", fixture.food(), "八月家庭餐饮");

        AnalysisResponse analysis = analysisService.analysis(fixture.household().getId(), YearMonth.parse("2026-09"));

        assertThat(analysis.historyStatus()).isEqualTo("sufficient");
        assertThat(analysis.insights()).isEmpty();
    }

    private Fixture fixture() {
        Household household = householdRepository.save(new Household("分析测试家庭", TEST_TIME));
        FamilyMember kevin = memberRepository.save(new FamilyMember(household, "Kevin", "爸爸", TEST_TIME));
        Category food = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, TEST_TIME));
        Category transport = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "交通", "#17324D", true, TEST_TIME));
        return new Fixture(household, kevin, food, transport);
    }

    private void saveExpense(Fixture fixture, Long amountCents, String occurredOn, Category category, String note) {
        transactionRepository.save(new FinancialTransaction(
                fixture.household(),
                fixture.member(),
                category,
                TransactionKind.EXPENSE,
                amountCents,
                LocalDate.parse(occurredOn),
                "商家",
                "杭州",
                note,
                TEST_TIME,
                TEST_TIME));
    }

    private record Fixture(Household household, FamilyMember member, Category food, Category transport) {
    }
}
