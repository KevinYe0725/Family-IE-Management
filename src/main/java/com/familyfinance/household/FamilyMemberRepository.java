package com.familyfinance.household;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {

    List<FamilyMember> findByHouseholdOrderById(Household household);

    List<FamilyMember> findByHouseholdIdOrderById(Long householdId);

    Optional<FamilyMember> findByIdAndHouseholdId(Long id, Long householdId);
}
