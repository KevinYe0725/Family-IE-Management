package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
class CurrentMembershipTest {

    @Autowired
    CurrentMembership currentMembership;

    @Autowired
    AppUserRepository users;

    @Autowired
    HouseholdMembershipRepository memberships;

    @Autowired
    HouseholdRepository households;

    @Test
    @Transactional
    void activeMembershipResolvesTheHouseholdAndRole() {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();

        MembershipContext context = currentMembership.require(authenticatedPrincipal(demo));

        assertThat(context.userId()).isEqualTo(demo.getId());
        assertThat(context.householdId()).isEqualTo(demo.getHousehold().getId());
        assertThat(context.role()).isEqualTo(HouseholdRole.OWNER);
    }

    @Test
    @Transactional
    void suspendedMembershipCannotResolveHousehold() {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        HouseholdMembership membership = memberships.findByUserIdAndStatus(demo.getId(), MembershipStatus.ACTIVE)
                .get(0);
        membership.suspend();

        assertThatThrownBy(() -> currentMembership.require(authenticatedPrincipal(demo)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @Transactional
    void multipleActiveMembershipsCannotResolveHousehold() {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        Household secondHousehold = households.save(new Household("第二家庭", Instant.parse("2026-09-02T00:00:00Z")));
        memberships.saveAndFlush(new HouseholdMembership(
                secondHousehold,
                demo,
                HouseholdRole.MEMBER,
                MembershipStatus.ACTIVE,
                Instant.parse("2026-09-02T00:00:00Z")));

        assertThatThrownBy(() -> currentMembership.require(authenticatedPrincipal(demo)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static Authentication authenticatedPrincipal(AppUser user) {
        FamilyUserPrincipal principal = new FamilyUserPrincipal(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash());
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), principal.getAuthorities());
    }
}
