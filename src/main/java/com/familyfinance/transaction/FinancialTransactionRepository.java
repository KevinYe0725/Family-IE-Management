package com.familyfinance.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long> {

    boolean existsByHouseholdIdAndMemberId(Long householdId, Long memberId);

    boolean existsByHouseholdIdAndCategoryId(Long householdId, Long categoryId);
}
