package com.familyfinance.investment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentAccountRepository extends JpaRepository<InvestmentAccount, Long> {

    Optional<InvestmentAccount> findByIdAndHouseholdId(Long id, Long householdId);

    Page<InvestmentAccount> findByHouseholdIdAndArchivedAtIsNull(Long householdId, Pageable pageable);

    Page<InvestmentAccount> findByHouseholdIdAndArchivedAtIsNotNull(Long householdId, Pageable pageable);

    boolean existsByHouseholdIdAndName(Long householdId, String name);

    boolean existsByHouseholdIdAndNameAndIdNot(Long householdId, String name, Long id);
}
