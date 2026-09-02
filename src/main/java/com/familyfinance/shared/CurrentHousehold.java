package com.familyfinance.shared;

import com.familyfinance.family.CurrentMembership;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentHousehold {

    private final CurrentMembership currentMembership;

    public CurrentHousehold(CurrentMembership currentMembership) {
        this.currentMembership = currentMembership;
    }

    public long id(Authentication authentication) {
        return currentMembership.require(authentication).householdId();
    }
}
