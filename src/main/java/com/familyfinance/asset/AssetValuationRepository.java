package com.familyfinance.asset;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetValuationRepository extends JpaRepository<AssetValuation, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from AssetValuation v where v.id=:id and v.household.id=:household")
    Optional<AssetValuation> findCurrent(long id,long household);

    Optional<AssetValuation> findFirstByAssetIdOrderByValuedOnDescFetchedAtDescIdDesc(Long assetId);

    Page<AssetValuation> findByHouseholdIdAndAssetId(Long householdId, Long assetId, Pageable pageable);

    long countByHouseholdIdAndAssetId(Long householdId, Long assetId);
}
