package com.familyfinance.budget;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BudgetConcurrencyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired BudgetRepository budgets;
    @Autowired BudgetRevisionRepository revisions;
    @MockitoSpyBean FamilyLockService locks;

    @Test
    void adminDemotionWinningTheHouseholdLockDeniesStaleBudgetCreate() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, uniqueEmail("budget-race-admin"), HouseholdRole.ADMIN);
        var adminUser = users.findAll().stream().filter(user -> user.getEmail().startsWith("budget-race-admin"))
                .findFirst().orElseThrow();
        long householdId = adminUser.getHousehold().getId();
        long membershipId = memberships.findByUserIdAndStatus(adminUser.getId(), MembershipStatus.ACTIVE)
                .get(0).getId();
        CountDownLatch reachedLock = new CountDownLatch(1);
        CountDownLatch allowLock = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    if (Thread.currentThread().getName().equals("budget-after-demotion")) {
                        reachedLock.countDown();
                        await(allowLock);
                    }
                    return invocation.callRealMethod();
                })
                .when(locks).lockActiveHousehold(householdId);

        ExecutorService executor = namedExecutor("budget-after-demotion");
        try {
            Future<MvcResult> result = executor.submit(() -> mvc.perform(post("/api/budgets")
                            .session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(totalBody("2027-01", "100.00")))
                    .andReturn());
            assertThat(reachedLock.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(patch("/api/family/memberships/{id}", membershipId)
                            .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MEMBER\"}"))
                    .andExpect(status().isOk());
            allowLock.countDown();

            assertThat(result.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
            assertThat(budgets.findAll()).noneMatch(budget -> budget.getPeriodMonth().toString().equals("2027-01"));
        } finally {
            allowLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateCreatesCommitExactlyOneActiveBudget() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, uniqueEmail("budget-duplicate-admin"), HouseholdRole.ADMIN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> requestAtBarrier(
                    ready, start, () -> mvc.perform(post("/api/budgets").session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(totalBody("2027-02", "100.00")))
                            .andReturn()));
            Future<MvcResult> second = executor.submit(() -> requestAtBarrier(
                    ready, start, () -> mvc.perform(post("/api/budgets").session(admin).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(totalBody("2027-02", "200.00")))
                            .andReturn()));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .stream().map(result -> result.getResponse().getStatus()).toList();
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(budgets.findAll().stream()
                    .filter(Budget::isActive)
                    .filter(budget -> budget.getPeriodMonth().toString().equals("2027-02")))
                    .hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentUpdatesWithOneVersionCommitOneRevisionAndRejectTheStaleWriter() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, uniqueEmail("budget-update-admin"), HouseholdRole.ADMIN);
        MvcResult created = mvc.perform(post("/api/budgets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(totalBody("2027-03", "100.00")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode initial = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        long id = initial.path("id").asLong();
        int version = initial.path("version").asInt();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> requestAtBarrier(ready, start,
                    () -> update(owner, id, version, "150.00")));
            Future<MvcResult> second = executor.submit(() -> requestAtBarrier(ready, start,
                    () -> update(admin, id, version, "175.00")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus()).toList())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst().orElseThrow();
            assertThat(objectMapper.readTree(conflict.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("STALE_VERSION");
            Budget saved = budgets.findById(id).orElseThrow();
            assertThat(saved.getVersion()).isEqualTo(version + 1);
            assertThat(saved.getAmountCents()).isIn(15000L, 17500L);
            assertThat(revisions.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MvcResult update(MockHttpSession session, long id, int version, String amount) throws Exception {
        return mvc.perform(patch("/api/budgets/{id}", id).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"amount\":\"" + amount + "\"}"))
                .andReturn();
    }

    private static MvcResult requestAtBarrier(
            CountDownLatch ready, CountDownLatch start, ThrowingRequest request) throws Exception {
        ready.countDown();
        await(start);
        return request.run();
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"预算管理员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static String totalBody(String month, String amount) {
        return "{\"periodMonth\":\"" + month + "\",\"scopeType\":\"TOTAL\",\"amount\":\"" + amount + "\"}";
    }

    private static String uniqueEmail(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime(), 36) + "@example.com";
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent request did not resume");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRequest { MvcResult run() throws Exception; }
}
