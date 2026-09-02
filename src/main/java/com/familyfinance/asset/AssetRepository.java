package com.familyfinance.asset;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByIdAndHouseholdId(Long id, Long householdId);

    Page<Asset> findByHouseholdIdAndStatus(Long householdId, AssetStatus status, Pageable pageable);

    Page<Asset> findByHouseholdIdAndTypeAndStatus(
            Long householdId, AssetType type, AssetStatus status, Pageable pageable);
}
