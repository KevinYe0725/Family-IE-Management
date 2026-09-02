package com.familyfinance.family;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdMembershipRepository extends JpaRepository<HouseholdMembership, Long> {

    List<HouseholdMembership> findByUserIdAndStatus(Long userId, MembershipStatus status);
}
