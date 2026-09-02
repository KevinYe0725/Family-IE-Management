package com.familyfinance.family;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface HouseholdMembershipRepository extends JpaRepository<HouseholdMembership, Long> {

    List<HouseholdMembership> findByUserIdAndStatus(Long userId, MembershipStatus status);

    List<HouseholdMembership> findByHouseholdIdOrderById(Long householdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<HouseholdMembership> findByIdAndHouseholdId(Long id, Long householdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<HouseholdMembership> findByHouseholdId(Long householdId);
}
