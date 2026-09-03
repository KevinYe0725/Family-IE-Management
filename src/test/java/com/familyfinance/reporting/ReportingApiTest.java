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
class ReportingApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void dashboardReturnsDeterministicSeptemberSeedStatistics() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/dashboard")
                        .session(session)
                        .param("month", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.income").value("28000.00"))
                .andExpect(jsonPath("$.data.summary.expense").value("484.80"))
                .andExpect(jsonPath("$.data.summary.balance").value("27515.20"))
                .andExpect(jsonPath("$.data.daily.length()").value(3))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-09-05"))
                .andExpect(jsonPath("$.data.daily[0].income").value("28000.00"))
                .andExpect(jsonPath("$.data.daily[1].expense").value("156.80"))
                .andExpect(jsonPath("$.data.daily[2].expense").value("328.00"))
                .andExpect(jsonPath("$.data.expenseByCategory[0].categoryName").value("购物"))
                .andExpect(jsonPath("$.data.expenseByCategory[0].amount").value("328.00"))
                .andExpect(jsonPath("$.data.expenseByCategory[0].sharePercent").value("67.7"))
                .andExpect(jsonPath("$.data.expenseByCategory[1].categoryName").value("餐饮"))
                .andExpect(jsonPath("$.data.expenseByCategory[1].sharePercent").value("32.3"))
                .andExpect(jsonPath("$.data.expenseByMember[0].memberName").value("Annie"))
                .andExpect(jsonPath("$.data.expenseByMember[0].amount").value("328.00"))
                .andExpect(jsonPath("$.data.expenseByMember[1].memberName").value("Lily"))
                .andExpect(jsonPath("$.data.expenseByMember[1].amount").value("156.80"));
    }

    @Test
    void analysisReturnsDeterministicSeptemberSeedInsights() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/analysis")
                        .session(session)
                        .param("month", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyStatus").value("sufficient"))
                .andExpect(jsonPath("$.data.insights.length()").value(3))
                .andExpect(jsonPath("$.data.insights[0].type").value("MONTHLY_DECREASE"))
                .andExpect(jsonPath("$.data.insights[0].metric").value("-79.3%"))
                .andExpect(jsonPath("$.data.insights[1].type").value("TOP_CATEGORY"))
                .andExpect(jsonPath("$.data.insights[1].metric").value("67.7%"))
                .andExpect(jsonPath("$.data.insights[1].message").value(org.hamcrest.Matchers.containsString("购物")))
                .andExpect(jsonPath("$.data.insights[2].type").value("LARGEST_EXPENSE"))
                .andExpect(jsonPath("$.data.insights[2].metric").value("328.00"))
                .andExpect(jsonPath("$.data.insights[2].message").value(org.hamcrest.Matchers.containsString("开学用品")));
    }

    @Test
    void reportingRejectsInvalidMonthWithFieldError() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/dashboard")
                        .session(session)
                        .param("month", "2026-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.month").value("月份格式必须是 YYYY-MM"));
    }

    @Test
    void analysisForEmptyMonthDoesNotFabricateConclusions() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/analysis")
                        .session(session)
                        .param("month", "2026-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyStatus").value("insufficient"))
                .andExpect(jsonPath("$.data.insights.length()").value(0));
    }

    @Test
    void portfolioIsAvailableToMembersAndReturnsBoundedEmptyView() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/portfolio").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positions.length()").value(0))
                .andExpect(jsonPath("$.data.totals.cost").value("0.00"))
                .andExpect(jsonPath("$.data.totals.marketValue").value("0.00"));
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
