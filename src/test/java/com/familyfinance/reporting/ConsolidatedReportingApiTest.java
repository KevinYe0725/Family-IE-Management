package com.familyfinance.reporting;

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
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Transactional
class ConsolidatedReportingApiTest {
    @Autowired MockMvc mvc;

    @Test
    void netWorthAndDebtEndpointsExposeBoundedServerCalculatedViews() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/net-worth").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asset").isString())
                .andExpect(jsonPath("$.data.liability").isString())
                .andExpect(jsonPath("$.data.netWorth").isString())
                .andExpect(jsonPath("$.data.allocation").isArray())
                .andExpect(jsonPath("$.data.investment.manualPrice").isBoolean())
                .andExpect(jsonPath("$.data.history").isArray());
        mvc.perform(get("/api/debt-analysis").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.debtRatioPercent").isString())
                .andExpect(jsonPath("$.data.loans").isArray());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
