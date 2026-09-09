package com.familyfinance.ledger;

import java.util.Optional;
import java.time.LocalDate;
import java.math.BigInteger;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, Long> {

    Optional<FinancialAccount> findFirstByHouseholdIdAndArchivedAtIsNullOrderById(Long householdId);

    Optional<FinancialAccount> findByIdAndHouseholdId(Long id, Long householdId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancialAccount a where a.id=:id and a.household.id=:householdId")
    Optional<FinancialAccount> findLockedByIdAndHouseholdId(@Param("id") Long id,@Param("householdId") Long householdId);

    Optional<FinancialAccount> findByIdAndHouseholdIdAndArchivedAtIsNull(Long id, Long householdId);

    Page<FinancialAccount> findByHouseholdIdAndArchivedAtIsNull(Long householdId, Pageable pageable);

    @Query("select a from FinancialAccount a where a.household.id=:householdId and a.bankAccount.id=:bankAccountId order by a.currency, a.id")
    List<FinancialAccount> findByBankAccountIdAndHouseholdIdOrderByCurrency(
            @Param("bankAccountId") Long bankAccountId, @Param("householdId") Long householdId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancialAccount a where a.household.id=:householdId and a.bankAccount.id=:bankAccountId order by a.currency, a.id")
    List<FinancialAccount> findLockedByBankAccountIdAndHouseholdId(
            @Param("bankAccountId") Long bankAccountId, @Param("householdId") Long householdId);

    boolean existsByHouseholdIdAndName(Long householdId, String name);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancialAccount a where a.household.id=:householdId and a.name=:name")
    Optional<FinancialAccount> findLockedByHouseholdIdAndName(
            @Param("householdId") Long householdId, @Param("name") String name);

    boolean existsByHouseholdIdAndNameAndIdNot(Long householdId, String name, Long id);

    @Query(value = """
            select account_row.id as accountId,
                   concat(account_row.opening_balance_cents + coalesce(sum(
                       case when transaction_row.kind = 'INCOME' then cast(transaction_row.amount_cents as decimal(38,0))
                            else -cast(transaction_row.amount_cents as decimal(38,0)) end), 0), '') as balanceCents
            from financial_accounts account_row
            left join financial_transactions transaction_row
              on transaction_row.account_id = account_row.id
             and transaction_row.household_id = account_row.household_id
             and transaction_row.occurred_on <= :occurredOn
            where account_row.household_id = :householdId
              and account_row.archived_at is null
            group by account_row.id, account_row.opening_balance_cents
            order by account_row.id
            """, nativeQuery = true)
    List<AccountBalanceRow> findActiveBalanceRowsByHouseholdIdAndOccurredOnBefore(
            @Param("householdId") Long householdId,
            @Param("occurredOn") LocalDate occurredOn);

    default List<AccountBalance> findActiveBalancesByHouseholdIdAndOccurredOnBefore(
            Long householdId, LocalDate occurredOn) {
        return findActiveBalanceRowsByHouseholdIdAndOccurredOnBefore(householdId, occurredOn).stream()
                .map(row -> new AccountBalance(row.getAccountId(), new BigInteger(row.getBalanceCents()).longValueExact()))
                .toList();
    }
}
