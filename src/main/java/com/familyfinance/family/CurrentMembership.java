package com.familyfinance.family;

import com.familyfinance.auth.FamilyUserPrincipal;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CurrentMembership {

    private final HouseholdMembershipRepository memberships;

    public CurrentMembership(HouseholdMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public MembershipContext require(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof FamilyUserPrincipal principal)) {
            throw new AccessDeniedException("当前登录身份无效");
        }
        List<HouseholdMembership> activeMemberships = memberships.findByUserIdAndStatus(
                principal.userId(), MembershipStatus.ACTIVE);
        if (activeMemberships.size() != 1) {
            throw new AccessDeniedException("当前账号没有唯一的有效家庭成员身份");
        }
        HouseholdMembership membership = activeMemberships.get(0);
        return new MembershipContext(
                membership.getHousehold().getId(),
                principal.userId(),
                membership.getRole());
    }
}
