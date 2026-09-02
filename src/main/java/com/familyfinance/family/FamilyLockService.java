package com.familyfinance.family;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import org.springframework.stereotype.Service;

/** Lock order for family mutations: household, membership rows, invite rows. JOIN discovers a token without locking only to identify its household, then follows that order. */
@Service
public class FamilyLockService {
    private final HouseholdRepository households;
    FamilyLockService(HouseholdRepository households) { this.households = households; }
    public Household lockActiveHousehold(long householdId) {
        Household household = lockHousehold(householdId);
        if (!"ACTIVE".equals(household.getStatus())) throw new ResourceConflictException("HOUSEHOLD_ARCHIVED", "家庭已归档");
        return household;
    }
    public Household lockHousehold(long householdId) { return households.findLockedById(householdId).orElseThrow(() -> new ResourceNotFoundException("家庭不存在")); }
}
