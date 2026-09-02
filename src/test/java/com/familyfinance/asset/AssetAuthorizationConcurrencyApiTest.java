package com.familyfinance.asset;

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
import org.springframework.jdbc.core.JdbcTemplate;
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
class AssetAuthorizationConcurrencyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean FamilyLockService locks;

    @Test
    void adminDemotionCompletingBeforeTheHouseholdLockDeniesStaleAssetCreate() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        String email = "asset-race-admin@example.com";
        MockHttpSession admin = join(owner, email, HouseholdRole.ADMIN);
        var adminUser = users.findByEmail(email).orElseThrow();
        long householdId = adminUser.getHousehold().getId();
        long membershipId = memberships.findByUserIdAndStatus(adminUser.getId(), MembershipStatus.ACTIVE)
                .get(0).getId();
        CountDownLatch reachedLock = new CountDownLatch(1);
        CountDownLatch allowLock = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
                    if (Thread.currentThread().getName().equals("asset-after-demotion")) {
                        reachedLock.countDown();
                        await(allowLock);
                    }
                    return invocation.callRealMethod();
                })
                .when(locks).lockActiveHousehold(householdId);

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asset-after-demotion");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<MvcResult> result = executor.submit(() -> mvc.perform(post("/api/assets")
                            .session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"降级后资产","type":"OTHER","ownerMemberId":null,
                                     "acquiredOn":null,"purchaseValue":null,"currentValue":"1.00"}
                                    """))
                    .andReturn());
            assertThat(reachedLock.await(5, TimeUnit.SECONDS)).isTrue();

            mvc.perform(patch("/api/family/memberships/{id}", membershipId)
                            .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"MEMBER\"}"))
                    .andExpect(status().isOk());
            allowLock.countDown();

            MvcResult denied = result.get(5, TimeUnit.SECONDS);
            assertThat(denied.getResponse().getStatus()).isEqualTo(403);
            assertThat(objectMapper.readTree(denied.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(jdbc.queryForObject(
                    "select count(*) from assets where household_id=? and name='降级后资产'",
                    Long.class, householdId)).isZero();
        } finally {
            allowLock.countDown();
            executor.shutdownNow();
        }
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"资产管理员","password":"family-pass-2026",
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent request did not resume");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
