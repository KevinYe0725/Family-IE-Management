package com.familyfinance.shared;

import com.familyfinance.auth.FamilyUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentHousehold {

    public long id(Authentication authentication) {
        FamilyUserPrincipal principal = (FamilyUserPrincipal) authentication.getPrincipal();
        return principal.householdId();
    }
}
