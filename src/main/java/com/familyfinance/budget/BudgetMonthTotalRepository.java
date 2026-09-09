package com.familyfinance.budget;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetMonthTotalRepository extends JpaRepository<BudgetMonthTotal, Long> {

    Optional<BudgetMonthTotal> findByHouseholdIdAndPeriodMonth(Long householdId, String periodMonth);
}
