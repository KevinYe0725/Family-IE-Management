package com.familyfinance.asset;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetValuationRepository extends JpaRepository<AssetValuation, Long> {

    Optional<AssetValuation> findByAssetIdAndValuedOnAndSource(
            Long assetId, LocalDate valuedOn, AssetValuationSource source);

    Optional<AssetValuation> findFirstByAssetIdOrderByValuedOnDescFetchedAtDescIdDesc(Long assetId);

    Page<AssetValuation> findByHouseholdIdAndAssetId(Long householdId, Long assetId, Pageable pageable);
}
