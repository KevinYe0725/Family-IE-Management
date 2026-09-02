package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FamilyMutationAuthorizationConcurrencyTest {

    @Autowired FamilyManagementService family;
    @Autowired InviteService inviteService;
    @Autowired HouseholdRepository households;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @MockitoSpyBean FamilyLockService locks;

    @Test
    void ownershipTransferCompletingBeforeRenameLockDeniesFormerOwner() throws Exception {
        FamilyFixture fixture = familyWithMember("rename-race");

        Throwable outcome = runPausedBeforeHouseholdLock("rename-after-transfer", fixture.household().getId(),
                () -> family.rename(auth(fixture.owner()), new MembershipController.RenameRequest("不应生效")),
                () -> family.transferOwnership(auth(fixture.owner()),
                        new MembershipController.TransferRequest(fixture.memberMembership().getId())));

        assertThat(outcome).isInstanceOf(AccessDeniedException.class);
        assertThat(households.findById(fixture.household().getId()).orElseThrow().getName())
                .isEqualTo("rename-race");
    }

    @Test
    void ownershipTransferCompletingBeforeAdminInviteCreationLockDeniesFormerOwner() throws Exception {
        FamilyFixture fixture = familyWithMember("create-admin-race");
        long inviteCount = inviteService.list(auth(fixture.owner()), 0, 50).items().size();

        Throwable outcome = runPausedBeforeHouseholdLock("admin-invite-after-transfer", fixture.household().getId(),
                () -> inviteService.create(auth(fixture.owner()), 1, HouseholdRole.ADMIN),
                () -> family.transferOwnership(auth(fixture.owner()),
                        new MembershipController.TransferRequest(fixture.memberMembership().getId())));

        assertThat(outcome).isInstanceOf(AccessDeniedException.class);
        assertThat(inviteService.list(auth(fixture.member()), 0, 50).items()).hasSize((int) inviteCount);
    }

    @Test
    void ownershipTransferCompletingBeforeAdminInviteRevocationLockDeniesFormerOwner() throws Exception {
        FamilyFixture fixture = familyWithMember("revoke-admin-race");
        long inviteId = inviteService.create(auth(fixture.owner()), 1, HouseholdRole.ADMIN).id();

        Throwable outcome = runPausedBeforeHouseholdLock("admin-revoke-after-transfer", fixture.household().getId(),
                () -> inviteService.revoke(auth(fixture.owner()), inviteId),
                () -> family.transferOwnership(auth(fixture.owner()),
                        new MembershipController.TransferRequest(fixture.memberMembership().getId())));

        assertThat(outcome).isInstanceOf(AccessDeniedException.class);
        assertThat(inviteService.list(auth(fixture.member()), 0, 50).items()).singleElement()
                .satisfies(invite -> assertThat(invite.revokedAt()).isNull());
    }

    @Test
    void adminDemotionCompletingBeforeMemberInviteCreationLockDeniesFormerAdmin() throws Exception {
        FamilyFixture fixture = familyWithMember("demote-admin-race", HouseholdRole.ADMIN);

        Throwable outcome = runPausedBeforeHouseholdLock("member-invite-after-demotion", fixture.household().getId(),
                () -> inviteService.create(auth(fixture.member()), 1, HouseholdRole.MEMBER),
                () -> family.updateRole(auth(fixture.owner()), fixture.memberMembership().getId(),
                        new MembershipController.RoleRequest(HouseholdRole.MEMBER)));

        assertThat(outcome).isInstanceOf(AccessDeniedException.class);
        assertThat(inviteService.list(auth(fixture.owner()), 0, 50).items()).isEmpty();
    }

    private FamilyFixture familyWithMember(String householdName) {
        return familyWithMember(householdName, HouseholdRole.MEMBER);
    }

    private FamilyFixture familyWithMember(String householdName, HouseholdRole memberRole) {
        Household household = households.save(new Household(householdName, clock.instant()));
        AppUser owner = saveUser(household, householdName + "-owner@example.com", "原所有者");
        AppUser member = saveUser(household, householdName + "-member@example.com", "目标成员");
        memberships.save(new HouseholdMembership(
                household, owner, HouseholdRole.OWNER, MembershipStatus.ACTIVE, clock.instant()));
        HouseholdMembership memberMembership = memberships.save(new HouseholdMembership(
                household, member, memberRole, MembershipStatus.ACTIVE, clock.instant()));
        return new FamilyFixture(household, owner, member, memberMembership);
    }

    private AppUser saveUser(Household household, String email, String displayName) {
        return users.save(new AppUser(
                household, email, email, displayName, passwordEncoder.encode("family-pass-2026"), clock.instant()));
    }

    private Throwable runPausedBeforeHouseholdLock(
            String workerName,
            long householdId,
            ThrowingRunnable staleMutation,
            ThrowingRunnable winningMutation) throws Exception {
        CountDownLatch reachedLock = new CountDownLatch(1);
        CountDownLatch allowLock = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    if (Thread.currentThread().getName().equals(workerName)) {
                        reachedLock.countDown();
                        await(allowLock);
                    }
                    return invocation.callRealMethod();
                })
                .when(locks).lockActiveHousehold(householdId);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, workerName);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Throwable> result = executor.submit(() -> {
                try {
                    staleMutation.run();
                    return null;
                } catch (Throwable exception) {
                    return exception;
                }
            });
            assertThat(reachedLock.await(5, TimeUnit.SECONDS)).isTrue();
            winningMutation.run();
            allowLock.countDown();
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            allowLock.countDown();
            executor.shutdownNow();
        }
    }

    private static UsernamePasswordAuthenticationToken auth(AppUser user) {
        return new UsernamePasswordAuthenticationToken(
                new FamilyUserPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash()),
                null,
                List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("worker did not resume");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record FamilyFixture(
            Household household,
            AppUser owner,
            AppUser member,
            HouseholdMembership memberMembership) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
