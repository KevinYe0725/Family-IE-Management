package com.familyfinance.family;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import jakarta.persistence.LockModeType;

public interface FamilyInviteRepository extends JpaRepository<FamilyInvite, Long> {
    Page<FamilyInvite> findByHouseholdId(Long householdId, Pageable pageable);
    List<FamilyInvite> findByHouseholdIdOrderByIdDesc(Long householdId);
    @Query("select i.household.id from FamilyInvite i where i.tokenHash = :tokenHash")
    Optional<Long> findHouseholdIdByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FamilyInvite> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FamilyInvite> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FamilyInvite> findByHouseholdId(Long householdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FamilyInvite i set i.usedCount = i.usedCount + 1 where i.id = :id and i.revokedAt is null and i.expiresAt > :now and i.usedCount < i.maxUses")
    int consumeIfAvailable(@Param("id") Long id, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FamilyInvite> findByIdAndHouseholdId(Long id, Long householdId);
}
