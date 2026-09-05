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
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Transactional
class AnnualPluginApiTest {
    @Autowired MockMvc mvc;

    @Test void requiresLogin() throws Exception {
        mvc.perform(get("/api/plugins")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/plugins/annual-stats")).andExpect(status().isUnauthorized());
    }

    @Test void reportsTwelveMonthsFromCurrentHouseholdAndRejectsInvalidYear() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/plugins").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("annual-stats"));
        mvc.perform(get("/api/plugins/annual-stats?year=2026&householdId=999").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.months.length()").value(12))
                .andExpect(jsonPath("$.data.summary.income").value("118000.00"))
                .andExpect(jsonPath("$.data.summary.averageIncome").value("9833.33"))
                .andExpect(jsonPath("$.data.months[8].expense").value("484.80"));
        mvc.perform(get("/api/plugins/annual-stats?year=1899").session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.year").exists());
        mvc.perform(get("/api/plugins/annual-stats?year=1900").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.income").value("0.00"));
    }

    @Test void anotherFamilyCannotReadDemoLedger() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("""
                {"email":"plugin-test@example.com","displayName":"插件测试","password":"family-pass-2026",
                 "mode":"CREATE","householdName":"插件隔离测试"}
                """)).andExpect(status().isCreated());
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", "plugin-test@example.com").param("password", "family-pass-2026"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/plugins/annual-stats?year=2026&householdId=1").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.income").value("0.00"));
    }
}
