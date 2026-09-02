package com.familyfinance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.family.FamilyLockService;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.AppUserRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AccountAuthorizationConcurrencyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired FinancialAccountRepository accounts;
    @MockitoSpyBean FamilyLockService locks;

    @Test
    void adminDemotionCompletingBeforeAccountLockDeniesTheStaleEndpointRequest() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, "account-race-admin@example.com", HouseholdRole.ADMIN);
        var adminUser = users.findByEmail("account-race-admin@example.com").orElseThrow();
        long householdId = adminUser.getHousehold().getId();
        long membershipId = memberships.findByUserIdAndStatus(adminUser.getId(), MembershipStatus.ACTIVE)
                .get(0).getId();
        CountDownLatch reachedLock = new CountDownLatch(1);
        CountDownLatch allowLock = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    if (Thread.currentThread().getName().equals("account-after-demotion")) {
                        reachedLock.countDown();
                        await(allowLock);
                    }
                    return invocation.callRealMethod();
                })
                .when(locks).lockActiveHousehold(householdId);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "account-after-demotion");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<MvcResult> accountResult = executor.submit(() -> mvc.perform(post("/api/accounts")
                            .session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(accountBody("降级后账户")))
                    .andReturn());
            assertThat(reachedLock.await(5, TimeUnit.SECONDS)).isTrue();

            mvc.perform(patch("/api/family/memberships/{id}", membershipId)
                            .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MEMBER\"}"))
                    .andExpect(status().isOk());
            allowLock.countDown();

            MvcResult denied = accountResult.get(5, TimeUnit.SECONDS);
            assertThat(denied.getResponse().getStatus()).isEqualTo(403);
            assertThat(objectMapper.readTree(denied.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(accounts.existsByHouseholdIdAndName(householdId, "降级后账户")).isFalse();
        } finally {
            allowLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentNormalizedDuplicateNamesYieldOneCreateAndOneConflict() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, "account-duplicate-admin@example.com", HouseholdRole.ADMIN);
        long householdId = users.findByEmail("demo@local.family").orElseThrow().getHousehold().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> createAtBarrier(
                    ready, start, owner, accountBody("  并发同名账户  ")));
            Future<MvcResult> second = executor.submit(() -> createAtBarrier(
                    ready, start, admin, accountBody("并发同名账户")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                    .containsExactlyInAnyOrder(201, 409);
            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst().orElseThrow();
            assertThat(objectMapper.readTree(conflict.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("RESOURCE_CONFLICT");
            assertThat(accounts.findAll().stream()
                    .filter(account -> account.getHousehold().getId().equals(householdId))
                    .filter(account -> account.getName().equals("并发同名账户")))
                    .hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MvcResult createAtBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            MockHttpSession session,
            String body) throws Exception {
        ready.countDown();
        await(start);
        return mvc.perform(post("/api/accounts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"账户管理员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String accountBody(String name) {
        return """
                {"name":"%s","type":"BANK","currency":"CNY","openingBalance":"0.00"}
                """.formatted(name);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent request did not resume");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
