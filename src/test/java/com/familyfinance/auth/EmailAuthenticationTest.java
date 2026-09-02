package com.familyfinance.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class EmailAuthenticationTest {

    @Autowired
    MockMvc mvc;

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

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
