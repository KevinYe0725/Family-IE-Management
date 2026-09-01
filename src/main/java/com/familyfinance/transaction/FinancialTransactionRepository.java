package com.familyfinance.transaction;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Sort;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>, JpaSpecificationExecutor<FinancialTransaction> {

    boolean existsByHouseholdIdAndMemberId(Long householdId, Long memberId);

    boolean existsByHouseholdIdAndCategoryId(Long householdId, Long categoryId);

    List<FinancialTransaction> findByHouseholdIdAndOccurredOnBetween(
            Long householdId,
            LocalDate from,
            LocalDate to,
            Sort sort);
}
