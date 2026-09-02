package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.identity.RegistrationService;
import com.familyfinance.shared.ResourceConflictException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArchiveConcurrencyTest {
    @Autowired InviteService inviteService;
    @Autowired FamilyManagementService family;
    @Autowired FamilyLockService locks;
    @Autowired RegistrationService registrations;
    @Autowired AppUserRepository users;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired FamilyInviteRepository invites;
    @Autowired HouseholdRepository households;
    @Autowired TransactionTemplate transactions;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void archiveWinsAgainstBlockedJoinAndLeavesNoActiveNewMembership() throws Exception {
        AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
        var auth = auth(owner);
        long householdId = owner.getHousehold().getId(); String householdName = households.findById(householdId).orElseThrow().getName();
        String token = inviteService.create(auth, 1, HouseholdRole.MEMBER).token();
        long before = memberships.count();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<String> outcome = transactions.execute(status -> {
                locks.lockActiveHousehold(householdId);
                Future<String> result = executor.submit(() -> {
                    entered.countDown();
                    try { registrations.join(new RegistrationService.JoinRegistration("blocked-join@example.com", "阻塞加入", "family-pass-2026", token)); return "SUCCESS"; }
                    catch (ResourceConflictException exception) { return exception.code(); }
                });
                await(entered);
                family.archive(auth, new MembershipController.ArchiveRequest(householdName));
                return result;
            });
            assertThat(outcome.get()).isEqualTo("HOUSEHOLD_ARCHIVED");
            assertThat(memberships.count()).isEqualTo(before);
            assertThat(invites.findByHouseholdIdOrderByIdDesc(householdId)).allSatisfy(i -> assertThat(i.getRevokedAt()).isNotNull());
        } finally { executor.shutdownNow(); }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void archiveWinsAgainstBlockedInviteCreationAndLeavesNoUsableInvite() throws Exception {
        AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
        var auth = auth(owner); long before = invites.count();
        long householdId = owner.getHousehold().getId(); String householdName = households.findById(householdId).orElseThrow().getName();
        ExecutorService executor = Executors.newSingleThreadExecutor(); CountDownLatch entered = new CountDownLatch(1);
        try {
            Future<String> outcome = transactions.execute(status -> {
                locks.lockActiveHousehold(householdId);
                Future<String> result = executor.submit(() -> { entered.countDown(); try { inviteService.create(auth, 1, HouseholdRole.MEMBER); return "SUCCESS"; } catch (ResourceConflictException exception) { return exception.code(); } });
                await(entered); family.archive(auth, new MembershipController.ArchiveRequest(householdName)); return result;
            });
            assertThat(outcome.get()).isEqualTo("HOUSEHOLD_ARCHIVED");
            assertThat(invites.count()).isEqualTo(before);
        } finally { executor.shutdownNow(); }
    }
    private static UsernamePasswordAuthenticationToken auth(AppUser user) { return new UsernamePasswordAuthenticationToken(new FamilyUserPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash()), null, List.of()); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker did not enter"); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); } }
}
