package com.familyfinance.investment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InvestmentTradeRepository
        extends JpaRepository<InvestmentTrade, Long>, JpaSpecificationExecutor<InvestmentTrade> {

    Optional<InvestmentTrade> findByIdAndHouseholdId(Long id, Long householdId);

    List<InvestmentTrade> findByHouseholdIdAndAccountIdAndSecurityId(
            Long householdId, Long accountId, Long securityId, Sort sort);
}
