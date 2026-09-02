package com.familyfinance.ledger;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, Long> {

    Optional<FinancialAccount> findFirstByHouseholdIdAndArchivedAtIsNullOrderById(Long householdId);

    Optional<FinancialAccount> findByIdAndHouseholdId(Long id, Long householdId);

    Optional<FinancialAccount> findByIdAndHouseholdIdAndArchivedAtIsNull(Long id, Long householdId);

    Page<FinancialAccount> findByHouseholdIdAndArchivedAtIsNull(Long householdId, Pageable pageable);

    boolean existsByHouseholdIdAndName(Long householdId, String name);

    boolean existsByHouseholdIdAndNameAndIdNot(Long householdId, String name, Long id);

    @Query(value = """
            select count(*)
            from recurring_rules
            where household_id = :householdId
              and account_id = :accountId
              and active = true
            """, nativeQuery = true)
    long countActiveRecurringReferences(
            @Param("householdId") Long householdId,
            @Param("accountId") Long accountId);
}
