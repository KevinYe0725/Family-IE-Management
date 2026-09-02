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
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"));
        mvc.perform(patch("/api/family/memberships/{id}", memberId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static long membershipIdFor(MvcResult result, String email) throws Exception {
        for (tools.jackson.databind.JsonNode membership : new tools.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).path("data")) {
            if (email.equals(membership.path("email").asText())) return membership.path("id").asLong();
        }
        throw new AssertionError("expected membership was absent");
    }
}
