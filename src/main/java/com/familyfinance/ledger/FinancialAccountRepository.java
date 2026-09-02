package com.familyfinance.ledger;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, Long> {

    Optional<FinancialAccount> findFirstByHouseholdIdAndArchivedAtIsNullOrderById(Long householdId);
}
