package com.familyfinance.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    List<BankAccount> findByHouseholdIdAndArchivedAtIsNullOrderByIdDesc(Long householdId);

    Optional<BankAccount> findByIdAndHouseholdId(Long id, Long householdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BankAccount b where b.id=:id and b.household.id=:householdId")
    Optional<BankAccount> findLockedByIdAndHouseholdId(
            @Param("id") Long id, @Param("householdId") Long householdId);
}
