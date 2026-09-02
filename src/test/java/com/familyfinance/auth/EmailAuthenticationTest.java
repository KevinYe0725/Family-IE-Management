package com.familyfinance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import com.familyfinance.family.HouseholdMembership;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.jdbc.core.JdbcTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class EmailAuthenticationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    HouseholdMembershipRepository memberships;

    @Autowired
    HouseholdRepository households;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void emailLoginIsCaseInsensitiveAndReturnsMembership() throws Exception {
        MvcResult result = login("  DEMO@LOCAL.FAMILY  ", "demo1234");

        mvc.perform(get("/api/session").session(session(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("demo@local.family"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));
    }

    @Test
    void onlyExactDemoAliasIsAcceptedForLegacyLogin() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "Demo")
                        .param("password", "demo1234"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isOk());
    }

    @Test
    @Transactional
    void suspendedMembershipLoginCannotReuseAnAuthenticatedSession() throws Exception {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        HouseholdMembership membership = memberships.findByUserIdAndStatus(demo.getId(), MembershipStatus.ACTIVE)
                .get(0);
        membership.suspend();
        memberships.flush();

        assertRejectedLoginCannotUseProtectedApi();
    }

    @Test
    @Transactional
    void ambiguousMembershipLoginCannotReuseAnAuthenticatedSession() throws Exception {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        Household secondHousehold = households.save(new Household("第二家庭", Instant.parse("2026-09-02T00:00:00Z")));
        memberships.saveAndFlush(new HouseholdMembership(
                secondHousehold,
                demo,
                HouseholdRole.MEMBER,
                MembershipStatus.ACTIVE,
                Instant.parse("2026-09-02T00:00:00Z")));

        assertRejectedLoginCannotUseProtectedApi();
    }

    @Test
    @Transactional
    void userWithoutMembershipCannotReuseAnAuthenticatedSession() throws Exception {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        users.saveAndFlush(new AppUser(
                demo.getHousehold(),
                "without-membership",
                "without-membership@local.family",
                "无成员身份用户",
                passwordEncoder.encode("family-pass-2026"),
                Instant.parse("2026-09-02T00:00:00Z")));

        assertRejectedLoginCannotUseProtectedApi("without-membership@local.family", "family-pass-2026");
    }

    @Test
    @Transactional
    void nonActiveUserCannotAuthenticateAndGetsTheGenericLoginFailure() throws Exception {
        jdbc.update("update app_users set status='SUSPENDED' where email='demo@local.family'");

        String disabledBody = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"))
                .andReturn().getResponse().getContentAsString();
        String unknownBody = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "missing-status-user@example.com")
                        .param("password", "demo1234"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(disabledBody).isEqualTo(unknownBody);
    }

    @Test
    @Transactional
    void sessionEstablishedBeforeUserSuspensionIsInvalidatedOnTheNextApiRequest() throws Exception {
        MockHttpSession activeSession = session(login("demo", "demo1234"));
        jdbc.update("update app_users set status='SUSPENDED' where email='demo@local.family'");

        mvc.perform(get("/api/session").session(activeSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        assertThat(activeSession.isInvalid()).isTrue();
    }

    @Test
    void loginRateLimitIsAccountNonEnumeratingAndScopedByNormalizedIdentifierAndRemoteIp() throws Exception {
        String knownBody = exhaustLoginLimit(" DEMO@LOCAL.FAMILY ", "wrong-password", "203.0.113.30");
        String unknownBody = exhaustLoginLimit("missing-login@example.com", "wrong-password", "203.0.113.30");

        assertThat(knownBody).isEqualTo(unknownBody);
        mvc.perform(loginRequest("other-login@example.com", "wrong-password", "203.0.113.30"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        mvc.perform(loginRequest("demo", "wrong-password", "203.0.113.31"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
    }

    @Test
    void successfulExactDemoLoginResetsFailuresAndKeepsItsSessionUsable() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(loginRequest("demo", "wrong-password", "203.0.113.32"))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult successful = mvc.perform(loginRequest("demo", "demo1234", "203.0.113.32"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("demo"))
                .andReturn();
        mvc.perform(get("/api/session").session(session(successful)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("demo@local.family"));
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(loginRequest("demo", "wrong-password", "203.0.113.32"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(loginRequest("demo", "wrong-password", "203.0.113.32"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("LOGIN_RATE_LIMITED"));
    }

    private String exhaustLoginLimit(String username, String password, String remoteIp) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(loginRequest(username, password, remoteIp))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        }
        return mvc.perform(loginRequest(username, password, remoteIp))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.error.message").value("登录暂时无法完成"))
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            String username, String password, String remoteIp) {
        return post("/api/auth/login")
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(remoteIp);
                    return request;
                })
                .param("username", username)
                .param("password", password);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void assertRejectedLoginCannotUseProtectedApi() throws Exception {
        assertRejectedLoginCannotUseProtectedApi("demo", "demo1234");
    }

    private void assertRejectedLoginCannotUseProtectedApi(String username, String password) throws Exception {
        MockHttpSession browserSession = new MockHttpSession();
        MvcResult rejected = mvc.perform(post("/api/auth/login")
                        .session(browserSession)
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(rejected.getResponse().getContentAsString()).contains("LOGIN_FAILED");

        MockHttpSession returnedSession = session(rejected);
        assertThat(returnedSession).isNotNull();
        assertThat(returnedSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        mvc.perform(get("/api/session").session(returnedSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
    }

    private static MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
