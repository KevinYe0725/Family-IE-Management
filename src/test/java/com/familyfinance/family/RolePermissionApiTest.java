package com.familyfinance.family;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RolePermissionApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void ownerCanRenameAndArchiveFamilyOnlyWithExactCurrentName() throws Exception {
        MockHttpSession owner = login();
        mvc.perform(get("/api/family").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(patch("/api/family").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新的家庭\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新的家庭"));
        mvc.perform(delete("/api/family").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmName\":\"错误家庭\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/family").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmName\":\"新的家庭\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(owner)).andExpect(status().isUnauthorized());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void currentOwnerTransfersOwnershipBySwappingRoles() throws Exception {
        MockHttpSession owner = login();
        String token = new tools.jackson.databind.ObjectMapper().readTree(mvc.perform(post("/api/family/invites")
                        .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"new-owner@example.com","displayName":"新所有者","password":"family-pass-2026","mode":"JOIN","inviteToken":"%s"}
                """.formatted(token))).andExpect(status().isCreated());
        MvcResult membershipList = mvc.perform(get("/api/family/memberships").session(owner))
                .andExpect(status().isOk()).andReturn();
        long memberId = membershipIdFor(membershipList, "new-owner@example.com");
        mvc.perform(post("/api/family/transfer-ownership").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"membershipId\":" + memberId + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/family/memberships").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.email == 'demo@local.family')].role").value("MEMBER"));
        mvc.perform(patch("/api/family/memberships/{id}", memberId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void onlyOwnerCanPromoteOrDemoteAdminsAndMembersCannotPatchRoles() throws Exception {
        MockHttpSession owner = login();
        join(owner, "role-admin@example.com", "ADMIN");
        join(owner, "role-member@example.com", "MEMBER");
        MockHttpSession admin = login("role-admin@example.com", "family-pass-2026");
        MockHttpSession member = login("role-member@example.com", "family-pass-2026");
        MvcResult list = mvc.perform(get("/api/family/memberships").session(owner))
                .andExpect(status().isOk()).andReturn();
        long ownerId = membershipIdFor(list, "demo@local.family");
        long adminId = membershipIdFor(list, "role-admin@example.com");
        long memberId = membershipIdFor(list, "role-member@example.com");

        patchRole(admin, memberId, "MEMBER").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        patchRole(member, adminId, "ADMIN").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        patchRole(owner, memberId, "ADMIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        patchRole(owner, adminId, "MEMBER").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
        patchRole(owner, ownerId, "MEMBER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/family/memberships").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.email == 'demo@local.family')].role").value("OWNER"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void crossHouseholdRolePatchUsesTheSameNonLeakingNotFoundEnvelope() throws Exception {
        MockHttpSession owner = login();
        registerCreate("foreign-owner@example.com", "外部家庭");
        MockHttpSession foreignOwner = login("foreign-owner@example.com", "family-pass-2026");
        MvcResult foreignList = mvc.perform(get("/api/family/memberships").session(foreignOwner))
                .andExpect(status().isOk()).andReturn();
        long foreignMembershipId = membershipIdFor(foreignList, "foreign-owner@example.com");

        String foreignBody = patchRole(owner, foreignMembershipId, "ADMIN")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknownBody = patchRole(owner, Long.MAX_VALUE, "ADMIN")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(foreignBody).isEqualTo(unknownBody)
                .doesNotContain("foreign-owner@example.com").doesNotContain("外部家庭");
    }

    private void join(MockHttpSession owner, String email, String role) throws Exception {
        String token = new tools.jackson.databind.ObjectMapper().readTree(mvc.perform(post("/api/family/invites")
                        .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","displayName":"角色成员","password":"family-pass-2026","mode":"JOIN","inviteToken":"%s"}
                """.formatted(email, token))).andExpect(status().isCreated());
    }

    private void registerCreate(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","displayName":"外部所有者","password":"family-pass-2026","mode":"CREATE","householdName":"%s"}
                """.formatted(email, householdName))).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions patchRole(
            MockHttpSession session, long membershipId, String role) throws Exception {
        return mvc.perform(patch("/api/family/memberships/{id}", membershipId).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"));
    }

    private MockHttpSession login() throws Exception {
        return login("demo", "demo1234");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static long membershipIdFor(MvcResult result, String email) throws Exception {
        for (tools.jackson.databind.JsonNode membership : new tools.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).path("data").path("items")) {
            if (email.equals(membership.path("email").asText())) return membership.path("id").asLong();
        }
        throw new AssertionError("expected membership was absent");
    }
}
