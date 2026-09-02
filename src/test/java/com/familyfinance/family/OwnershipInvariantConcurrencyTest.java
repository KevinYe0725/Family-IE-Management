package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OwnershipInvariantConcurrencyTest {
    @Autowired FamilyManagementService family;
    @Autowired HouseholdRepository households;
    @Autowired AppUserRepository users;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;

    @Test
    void transferFindsOwnerByUserIdWhenUserAndMembershipIdsAreDeliberatelyMisaligned() {
        Household household = households.save(new Household("错位编号家庭", clock.instant()));
        AppUser owner = saveUser(household, "misaligned-owner@example.com", "错位所有者");
        AppUser target = saveUser(household, "misaligned-target@example.com", "目标成员");
        HouseholdMembership targetMembership = memberships.save(new HouseholdMembership(
                household, target, HouseholdRole.MEMBER, MembershipStatus.ACTIVE, clock.instant()));
        HouseholdMembership ownerMembership = memberships.save(new HouseholdMembership(
                household, owner, HouseholdRole.OWNER, MembershipStatus.ACTIVE, clock.instant()));
        assertThat(owner.getId()).isEqualTo(targetMembership.getId());
        assertThat(owner.getId()).isNotEqualTo(ownerMembership.getId());

        family.transferOwnership(auth(owner), new MembershipController.TransferRequest(targetMembership.getId()));

        List<HouseholdMembership> result = memberships.findByHouseholdIdOrderById(household.getId());
        assertThat(roleFor(result, owner.getId())).isEqualTo(HouseholdRole.MEMBER);
        assertThat(roleFor(result, target.getId())).isEqualTo(HouseholdRole.OWNER);
        assertExactlyOneActiveOwner(result);
    }

    @Test
    void concurrentTransfersAllowOnlyOneSuccessAndPreserveExactlyOneOwner() throws Exception {
        Household household = households.save(new Household("并发转让家庭", clock.instant()));
        AppUser owner = saveUser(household, "transfer-owner@example.com", "原所有者");
        AppUser first = saveUser(household, "transfer-first@example.com", "第一目标");
        AppUser second = saveUser(household, "transfer-second@example.com", "第二目标");
        memberships.save(new HouseholdMembership(household, owner, HouseholdRole.OWNER, MembershipStatus.ACTIVE, clock.instant()));
        HouseholdMembership firstMembership = memberships.save(new HouseholdMembership(household, first, HouseholdRole.MEMBER, MembershipStatus.ACTIVE, clock.instant()));
        HouseholdMembership secondMembership = memberships.save(new HouseholdMembership(household, second, HouseholdRole.MEMBER, MembershipStatus.ACTIVE, clock.instant()));

        List<String> outcomes = race(
                () -> transferOutcome(owner, firstMembership.getId()),
                () -> transferOutcome(owner, secondMembership.getId()));

        assertThat(outcomes).contains("SUCCESS_TRANSFER");
        assertThat(outcomes.stream().filter("SUCCESS_TRANSFER"::equals)).hasSize(1);
        assertExactlyOneActiveOwner(memberships.findByHouseholdIdOrderById(household.getId()));
    }

    @Test
    void concurrentTransferAndAdminDemotionLeaveTheTransferTargetAsSoleOwner() throws Exception {
        Household household = households.save(new Household("转让降级家庭", clock.instant()));
        AppUser owner = saveUser(household, "demotion-owner@example.com", "原所有者");
        AppUser target = saveUser(household, "demotion-target@example.com", "管理员目标");
        memberships.save(new HouseholdMembership(household, owner, HouseholdRole.OWNER, MembershipStatus.ACTIVE, clock.instant()));
        HouseholdMembership targetMembership = memberships.save(new HouseholdMembership(
                household, target, HouseholdRole.ADMIN, MembershipStatus.ACTIVE, clock.instant()));

        List<String> outcomes = race(
                () -> transferOutcome(owner, targetMembership.getId()),
                () -> demotionOutcome(owner, targetMembership.getId()));

        assertThat(outcomes).contains("SUCCESS_TRANSFER");
        List<HouseholdMembership> result = memberships.findByHouseholdIdOrderById(household.getId());
        assertThat(roleFor(result, target.getId())).isEqualTo(HouseholdRole.OWNER);
        assertExactlyOneActiveOwner(result);
    }

    private String transferOutcome(AppUser owner, long targetMembershipId) {
        try {
            family.transferOwnership(auth(owner), new MembershipController.TransferRequest(targetMembershipId));
            return "SUCCESS_TRANSFER";
        } catch (ResourceConflictException exception) {
            return exception.code();
        } catch (AccessDeniedException exception) {
            return "FORBIDDEN";
        }
    }

    private String demotionOutcome(AppUser owner, long targetMembershipId) {
        try {
            family.updateRole(auth(owner), targetMembershipId, new MembershipController.RoleRequest(HouseholdRole.MEMBER));
            return "SUCCESS_DEMOTION";
        } catch (RequestValidationException exception) {
            return "VALIDATION_ERROR";
        } catch (AccessDeniedException exception) {
            return "FORBIDDEN";
        }
    }

    private static List<String> race(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> firstResult = executor.submit(() -> runAtBarrier(ready, start, first));
            Future<String> secondResult = executor.submit(() -> runAtBarrier(ready, start, second));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstResult.get(), secondResult.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static String runAtBarrier(CountDownLatch ready, CountDownLatch start, ThrowingSupplier action) throws Exception {
        ready.countDown();
        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("race did not start");
        return action.get();
    }

    private AppUser saveUser(Household household, String email, String displayName) {
        Instant now = clock.instant();
        return users.save(new AppUser(household, email, email, displayName, passwordEncoder.encode("family-pass-2026"), now));
    }

    private static UsernamePasswordAuthenticationToken auth(AppUser user) {
        return new UsernamePasswordAuthenticationToken(
                new FamilyUserPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash()),
                null, List.of());
    }

    private static HouseholdRole roleFor(List<HouseholdMembership> values, long userId) {
        return values.stream().filter(value -> value.getUser().getId() == userId).findFirst().orElseThrow().getRole();
    }

    private static void assertExactlyOneActiveOwner(List<HouseholdMembership> values) {
        assertThat(values.stream().filter(value -> value.getStatus() == MembershipStatus.ACTIVE)
                .filter(value -> value.getRole() == HouseholdRole.OWNER)).hasSize(1);
    }

    @FunctionalInterface
    interface ThrowingSupplier { String get() throws Exception; }
}
