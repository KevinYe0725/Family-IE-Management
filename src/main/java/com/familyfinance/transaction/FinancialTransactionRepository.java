package com.familyfinance.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>, JpaSpecificationExecutor<FinancialTransaction> {

    boolean existsByHouseholdIdAndMemberId(Long householdId, Long memberId);

    boolean existsByHouseholdIdAndCategoryId(Long householdId, Long categoryId);
}
