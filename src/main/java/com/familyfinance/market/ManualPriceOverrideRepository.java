package com.familyfinance.market;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualPriceOverrideRepository extends JpaRepository<ManualPriceOverride, Long> {
    Optional<ManualPriceOverride> findByHouseholdIdAndSecurityIdAndEffectiveOn(Long householdId, Long securityId, LocalDate effectiveOn);
    Optional<ManualPriceOverride> findFirstByHouseholdIdAndSecurityIdAndEffectiveOnLessThanEqualOrderByEffectiveOnDescIdDesc(
            Long householdId, Long securityId, LocalDate effectiveOn);
    List<ManualPriceOverride> findByHouseholdIdAndSecurityIdInAndEffectiveOnLessThanEqual(
            Long householdId, Collection<Long> securityIds, LocalDate effectiveOn);
}
