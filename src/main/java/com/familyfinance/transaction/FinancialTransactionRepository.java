package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinancialTransactionRepository
        extends JpaRepository<FinancialTransaction, Long>, JpaSpecificationExecutor<FinancialTransaction> {

    boolean existsByHouseholdIdAndMemberId(Long householdId, Long memberId);

    boolean existsByHouseholdIdAndCategoryId(Long householdId, Long categoryId);

    @EntityGraph(attributePaths = {"member", "category", "category.parent"})
    List<FinancialTransaction> findByHouseholdIdAndOccurredOnBetween(
            Long householdId,
            LocalDate from,
            LocalDate to,
            Sort sort);

    @EntityGraph(attributePaths = {"member", "category", "category.parent"})
    List<FinancialTransaction> findByHouseholdIdAndKindAndOccurredOnBefore(
            Long householdId,
            TransactionKind kind,
            LocalDate before,
            Sort sort);
}
