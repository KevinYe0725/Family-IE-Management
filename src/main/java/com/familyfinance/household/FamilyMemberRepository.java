package com.familyfinance.household;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {

    List<FamilyMember> findByHouseholdOrderById(Household household);

    List<FamilyMember> findByHouseholdIdOrderById(Long householdId);

    Optional<FamilyMember> findByIdAndHouseholdId(Long id, Long householdId);
    Optional<FamilyMember> findFirstByHouseholdIdAndLinkedUserId(Long householdId, Long linkedUserId);

    @Query(value = """
            select count(*) from (
                select id from budgets where household_id=:householdId and member_id=:memberId
                union all
                select id from budget_revisions
                where household_id=:householdId
                  and (old_member_id=:memberId or new_member_id=:memberId)
            ) budget_refs
            """, nativeQuery = true)
    long countBudgetReferences(@Param("householdId") Long householdId, @Param("memberId") Long memberId);
}
