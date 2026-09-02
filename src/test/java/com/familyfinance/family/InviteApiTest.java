package com.familyfinance.family;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import com.familyfinance.household.AppUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.annotation.DirtiesContext;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InviteApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FamilyInviteRepository invites;
    @Autowired AppUserRepository users;

    @Test
    void ownerCreatesInviteButOnlyHashIsStored() throws Exception {
        MvcResult result = mvc.perform(post("/api/family/invites")
                        .session(login("demo", "demo1234"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxUses\":5,\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();

        String token = token(result);
        org.assertj.core.api.Assertions.assertThat(token).hasSizeGreaterThan(32);
        long inviteId = new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        FamilyInvite invite = invites.findById(inviteId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(invite.getTokenHash()).isEqualTo(InviteService.sha256(token));
        org.assertj.core.api.Assertions.assertThat(invite.getTokenHash()).doesNotContain(token);
        org.assertj.core.api.Assertions.assertThat(invite.getExpiresAt())
                .isEqualTo(invite.getCreatedAt().plus(java.time.Duration.ofDays(7)));
    }

    @Test
    void memberCannotCreateInviteAndOwnerCanRevokeIt() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        String token = createInvite(owner);
        registerJoin("member@example.com", token).andExpect(status().isCreated());
        MockHttpSession member = login("member@example.com", "family-pass-2026");

        mvc.perform(post("/api/family/invites").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        long inviteId = inviteId(owner);
        mvc.perform(delete("/api/family/invites/{id}", inviteId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        registerJoin("revoked@example.com", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITE_REVOKED"));
    }

    @Test
    void ownerAdminAndMemberInvitePermissionsFollowTheCompleteMatrix() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MvcResult adminInvite = createInvite(owner, "{\"role\":\"ADMIN\"}");
        registerJoin("invite-admin@example.com", token(adminInvite)).andExpect(status().isCreated());
        MockHttpSession admin = login("invite-admin@example.com", "family-pass-2026");

        MvcResult memberInvite = createInvite(admin, "{\"role\":\"MEMBER\"}");
        long memberInviteId = inviteId(memberInvite);
        mvc.perform(delete("/api/family/invites/{id}", memberInviteId).session(admin).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/family/invites").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("没有权限执行此操作"));
        mvc.perform(delete("/api/family/invites/{id}", inviteId(adminInvite)).session(admin).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/family/invites/{id}", inviteId(adminInvite)).session(owner).with(csrf()))
                .andExpect(status().isNoContent());

        MvcResult joinInvite = createInvite(owner, "{\"role\":\"MEMBER\"}");
        registerJoin("invite-member@example.com", token(joinInvite)).andExpect(status().isCreated());
        MockHttpSession member = login("invite-member@example.com", "family-pass-2026");
        mvc.perform(post("/api/family/invites").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/family/invites/{id}", inviteId(joinInvite)).session(member).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void crossHouseholdInviteRevocationLooksExactlyLikeAnUnknownInvite() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        registerCreate("other-owner@example.com", "另一个家庭").andExpect(status().isCreated());
        MockHttpSession otherOwner = login("other-owner@example.com", "family-pass-2026");
        long foreignInviteId = inviteId(createInvite(otherOwner, "{}"));

        String foreignBody = mvc.perform(delete("/api/family/invites/{id}", foreignInviteId)
                        .session(owner).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknownBody = mvc.perform(delete("/api/family/invites/{id}", Long.MAX_VALUE)
                        .session(owner).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(foreignBody).isEqualTo(unknownBody)
                .doesNotContain("other-owner@example.com").doesNotContain("另一个家庭");
    }

    @Test
    void invalidAndExpiredTokensUseStructuredInviteErrors() throws Exception {
        registerJoin("invalid@example.com", "not-a-real-token").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITE_INVALID"));
        var owner = users.findByEmail("demo@local.family").orElseThrow();
        String expired = "expired-token";
        invites.save(new FamilyInvite(owner.getHousehold(), InviteService.sha256(expired), HouseholdRole.MEMBER,
                Instant.now().minusSeconds(1), 1, owner, Instant.now().minusSeconds(10)));
        registerJoin("expired@example.com", expired).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITE_EXPIRED"));
    }

    @Test
    void inviteListIsBoundedAndStableDescending() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        for (int index = 0; index < 55; index++) createInvite(owner);
        mvc.perform(get("/api/family/invites").param("size", "999").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    private String createInvite(MockHttpSession session) throws Exception {
        return token(createInvite(session, "{}"));
    }

    private MvcResult createInvite(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/api/family/invites").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private long inviteId(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/family/invites").session(session))
                .andExpect(status().isOk()).andReturn();
        return new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path("data").path("items").get(0).path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions registerJoin(String email, String token) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","displayName":"加入成员","password":"family-pass-2026","mode":"JOIN","inviteToken":"%s"}
                        """.formatted(email, token)));
    }

    private org.springframework.test.web.servlet.ResultActions registerCreate(String email, String householdName) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","displayName":"其他所有者","password":"family-pass-2026","mode":"CREATE","householdName":"%s"}
                        """.formatted(email, householdName)));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).param("username", email).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String token(MvcResult result) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private static long inviteId(MvcResult result) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

}
