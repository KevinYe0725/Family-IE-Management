package com.familyfinance.transaction;

import com.familyfinance.category.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(value = """
            select cast(coalesce(sum(cast(transaction_row.amount_cents as numeric(38))), 0) as varchar)
            from financial_transactions transaction_row
            left join categories category_row
              on category_row.id = transaction_row.category_id
             and category_row.household_id = transaction_row.household_id
            where transaction_row.household_id = :householdId
              and transaction_row.kind = 'EXPENSE'
              and transaction_row.occurred_on >= :fromDate
              and transaction_row.occurred_on < :toDate
              and (
                  :scopeType = 'TOTAL'
                  or (:scopeType = 'MEMBER' and transaction_row.member_id = :memberId)
                  or (:scopeType = 'CATEGORY' and (
                      transaction_row.category_id = :categoryId
                      or (:rollupCategories = true and category_row.parent_id = :categoryId)
                  ))
              )
              and (transaction_row.source_type <> 'RECURRING' or exists (
                  select 1 from recurring_occurrences occurrence_row
                  where occurrence_row.id = transaction_row.source_id
                    and occurrence_row.household_id = transaction_row.household_id
                    and occurrence_row.status = 'CONFIRMED'
                    and occurrence_row.confirmed_transaction_id = transaction_row.id
              ))
            """, nativeQuery = true)
    String sumBudgetExpenseCents(
            @Param("householdId") Long householdId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("scopeType") String scopeType,
            @Param("categoryId") Long categoryId,
            @Param("memberId") Long memberId,
            @Param("rollupCategories") boolean rollupCategories);
}
