package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=false")
@Transactional
class FinancialTransactionRepositoryReportingTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void reportingMonthQueryFetchesMemberAndCategoryWithinExactHouseholdDateScope() {
        Household household = householdRepository.save(new Household("报表家庭", TEST_TIME));
        FamilyMember member = memberRepository.save(new FamilyMember(household, "Kevin", "爸爸", TEST_TIME));
        Category food = categoryRepository.save(new Category(
                household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, TEST_TIME));
        Household outsider = householdRepository.save(new Household("其他家庭", TEST_TIME));
        FamilyMember outsiderMember = memberRepository.save(new FamilyMember(outsider, "外部成员", "访客", TEST_TIME));
        Category outsiderFood = categoryRepository.save(new Category(
                outsider, TransactionKind.EXPENSE, "外部餐饮", "#17324D", true, TEST_TIME));

        transactionRepository.save(new FinancialTransaction(
                household,
                member,
                food,
                TransactionKind.EXPENSE,
                12000L,
                LocalDate.parse("2026-09-10"),
                "菜场",
                "杭州",
                "范围内",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.save(new FinancialTransaction(
                household,
                member,
                food,
                TransactionKind.EXPENSE,
                8000L,
                LocalDate.parse("2026-10-01"),
                "菜场",
                "杭州",
                "范围外日期",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.save(new FinancialTransaction(
                outsider,
                outsiderMember,
                outsiderFood,
                TransactionKind.EXPENSE,
                990000L,
                LocalDate.parse("2026-09-10"),
                "外部商家",
                "杭州",
                "范围外家庭",
                TEST_TIME,
                TEST_TIME));
        entityManager.flush();
        entityManager.clear();

        var transactions = transactionRepository.findByHouseholdIdAndOccurredOnBetween(
                household.getId(),
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-30"),
                Sort.by(Sort.Order.asc("occurredOn"), Sort.Order.asc("id")));

        assertThat(transactions).hasSize(1);
        FinancialTransaction transaction = transactions.get(0);
        assertThat(transaction.getAmountCents()).isEqualTo(12000L);

        PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistenceUnitUtil.isLoaded(transaction.getMember())).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(transaction.getCategory())).isTrue();
    }
}
