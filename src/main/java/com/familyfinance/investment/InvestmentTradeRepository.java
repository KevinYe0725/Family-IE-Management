package com.familyfinance.investment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestmentTradeRepository
        extends JpaRepository<InvestmentTrade, Long>, JpaSpecificationExecutor<InvestmentTrade> {

    Optional<InvestmentTrade> findByIdAndHouseholdId(Long id, Long householdId);

    List<InvestmentTrade> findByHouseholdIdAndAccountIdAndSecurityId(
            Long householdId, Long accountId, Long securityId, Sort sort);

    @Query("""
            select trade from InvestmentTrade trade join trade.account account
            where trade.household.id = :householdId and account.archivedAt is null
            order by trade.account.id, trade.security.id, trade.tradedOn, trade.id
            """)
    List<InvestmentTrade> findActiveAccountTradesByHouseholdId(@Param("householdId") Long householdId);
}
