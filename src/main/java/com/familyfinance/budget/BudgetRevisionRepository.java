package com.familyfinance.budget;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface BudgetRevisionRepository extends Repository<BudgetRevision, Long> {

    BudgetRevision save(BudgetRevision revision);

    void flush();

    long count();

    Page<BudgetRevision> findByHouseholdIdAndBudgetId(Long householdId, Long budgetId, Pageable pageable);
}
