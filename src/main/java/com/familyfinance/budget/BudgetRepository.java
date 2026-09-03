package com.familyfinance.budget;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    Optional<Budget> findByIdAndHouseholdId(Long id, Long householdId);

    Page<Budget> findByHouseholdId(Long householdId, Pageable pageable);

    Page<Budget> findByHouseholdIdAndPeriodMonth(Long householdId, String periodMonth, Pageable pageable);

    Page<Budget> findByHouseholdIdAndPeriodMonthAndActiveTrue(
            Long householdId, String periodMonth, Pageable pageable);
    java.util.List<Budget> findAllByHouseholdIdAndPeriodMonthAndActiveTrue(Long householdId, String periodMonth);

    boolean existsByHouseholdIdAndPeriodMonthAndScopeTypeAndCategoryIdAndMemberIdAndActiveTrue(
            Long householdId,
            String periodMonth,
            BudgetScopeType scopeType,
            Long categoryId,
            Long memberId);

    boolean existsByHouseholdIdAndPeriodMonthAndScopeTypeAndCategoryIdAndMemberIdAndActiveTrueAndIdNot(
            Long householdId,
            String periodMonth,
            BudgetScopeType scopeType,
            Long categoryId,
            Long memberId,
            Long id);
}
