package com.familyfinance.family;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface FamilyInviteRepository extends JpaRepository<FamilyInvite, Long> {
    List<FamilyInvite> findByHouseholdIdOrderByIdDesc(Long householdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FamilyInvite> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FamilyInvite> findByIdAndHouseholdId(Long id, Long householdId);
}
