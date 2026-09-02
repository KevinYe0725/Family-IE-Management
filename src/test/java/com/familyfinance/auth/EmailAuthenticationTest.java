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
