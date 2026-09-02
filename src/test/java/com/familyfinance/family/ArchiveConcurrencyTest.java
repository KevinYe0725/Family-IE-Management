package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArchiveConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired InviteService inviteService;
    @Autowired FamilyManagementService family;
    @MockitoSpyBean FamilyLockService locks;
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

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void archiveWaitingBehindOwnershipTransferDeniesFormerOwnerAndKeepsFamilyActive() throws Exception {
        AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
        var ownerAuthentication = auth(owner);
        long householdId = owner.getHousehold().getId();
        String householdName = households.findById(householdId).orElseThrow().getName();
        String token = inviteService.create(ownerAuthentication, 1, HouseholdRole.MEMBER).token();
        registrations.join(new RegistrationService.JoinRegistration(
                "archive-transfer-target@example.com", "新所有者", "family-pass-2026", token));
        AppUser target = users.findByEmail("archive-transfer-target@example.com").orElseThrow();
        long targetMembershipId = memberships.findByUserIdAndStatus(target.getId(), MembershipStatus.ACTIVE)
                .get(0).getId();
        MockHttpSession ownerSession = login();

        CountDownLatch archiveReachedHouseholdLock = new CountDownLatch(1);
        CountDownLatch allowArchiveToAcquireLock = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    if (Thread.currentThread().getName().equals("archive-after-transfer")) {
                        archiveReachedHouseholdLock.countDown();
                        await(allowArchiveToAcquireLock);
                    }
                    return invocation.callRealMethod();
                })
                .when(locks).lockHousehold(householdId);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "archive-after-transfer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<MvcResult> archiveResult = executor.submit(() -> mvc.perform(delete("/api/family")
                            .session(ownerSession).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmName\":\"" + householdName + "\"}"))
                    .andReturn());
            await(archiveReachedHouseholdLock);

            mvc.perform(post("/api/family/transfer-ownership").session(ownerSession).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":" + targetMembershipId + "}"))
                    .andExpect(status().isNoContent());
            allowArchiveToAcquireLock.countDown();

            MvcResult deniedArchive = archiveResult.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deniedArchive.getResponse().getStatus()).isEqualTo(403);
            assertThat(new tools.jackson.databind.ObjectMapper()
                    .readTree(deniedArchive.getResponse().getContentAsString()).path("error").path("code").asText())
                    .isEqualTo("FORBIDDEN");

            assertThat(households.findById(householdId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
            List<HouseholdMembership> finalMemberships = memberships.findByHouseholdIdOrderById(householdId);
            assertThat(finalMemberships.stream()
                    .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                    .filter(membership -> membership.getRole() == HouseholdRole.OWNER)).singleElement()
                    .satisfies(membership -> assertThat(membership.getUser().getId()).isEqualTo(target.getId()));
            assertThat(finalMemberships.stream()
                    .filter(membership -> membership.getUser().getId().equals(owner.getId())).findFirst().orElseThrow().getRole())
                    .isEqualTo(HouseholdRole.MEMBER);
        } finally {
            allowArchiveToAcquireLock.countDown();
            executor.shutdownNow();
        }
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static UsernamePasswordAuthenticationToken auth(AppUser user) { return new UsernamePasswordAuthenticationToken(new FamilyUserPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash()), null, List.of()); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("worker did not enter"); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); } }
}
