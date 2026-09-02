package com.familyfinance.identity;

import com.familyfinance.family.HouseholdRole;

public record RegisterResponse(String email, String displayName, String householdName, HouseholdRole role) {
}
