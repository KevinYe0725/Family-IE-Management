package com.familyfinance.family;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Serializes family mutations, then derives authorization from the locked current database state. */
@Service
class FamilyMutationAuthorization {

    private final CurrentMembership currentMembership;
    private final FamilyLockService locks;
    private final HouseholdMembershipRepository memberships;
    private final FamilyPermissionService permissions;
    private final EntityManager entityManager;
    private final HouseholdRepository households;

    FamilyMutationAuthorization(
            CurrentMembership currentMembership,
            FamilyLockService locks,
            HouseholdMembershipRepository memberships,
            FamilyPermissionService permissions,
            EntityManager entityManager,
            HouseholdRepository households) {
        this.currentMembership = currentMembership;
        this.locks = locks;
        this.memberships = memberships;
        this.permissions = permissions;
        this.entityManager = entityManager;
        this.households = households;
    }

    LockedFamilyAccess requireAdmin(Authentication authentication) {
        LockedFamilyAccess access = lockFresh(authentication);
        permissions.requireAdmin(access.context());
        return access;
    }

    LockedFamilyAccess requireOwner(Authentication authentication) {
        LockedFamilyAccess access = lockFresh(authentication);
        permissions.requireOwner(access.context());
        return access;
    }

    private LockedFamilyAccess lockFresh(Authentication authentication) {
        MembershipContext initial = currentMembership.require(authentication);
        locks.lockActiveHousehold(initial.householdId());
        entityManager.clear();
        Household household = households.findById(initial.householdId())
                .orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        HouseholdMembership membership = memberships
                .findByHouseholdIdAndUserIdAndStatus(
                        initial.householdId(), initial.userId(), MembershipStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("当前账号没有有效家庭成员身份"));
        MembershipContext fresh = new MembershipContext(
                initial.householdId(), initial.userId(), membership.getRole());
        return new LockedFamilyAccess(household, membership, fresh);
    }

    record LockedFamilyAccess(
            Household household,
            HouseholdMembership membership,
            MembershipContext context) {
    }
}
