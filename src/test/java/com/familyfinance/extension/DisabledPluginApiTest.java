package com.familyfinance.extension;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest(properties = {"app.seed.enabled=true", "app.plugins.annual-stats.enabled=false"})
@AutoConfigureMockMvc
class DisabledPluginApiTest {
    @Autowired MockMvc mvc;
    @Test void disabledPluginHasNoMenuOrApiWhileCoreRemainsAvailable() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/plugins").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/plugins/annual-stats?year=2026").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/dashboard?month=2026-09").session(session)).andExpect(status().isOk());
    }
}
