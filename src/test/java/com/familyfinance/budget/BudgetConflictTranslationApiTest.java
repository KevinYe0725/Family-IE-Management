package com.familyfinance.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class BudgetConflictTranslationApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoSpyBean BudgetRepository budgets;
    @Autowired BudgetRevisionRepository revisions;

    @Test
    void createTranslatesDatabaseIntegrityConflict() throws Exception {
        MockHttpSession session = login();
        Mockito.doThrow(new DataIntegrityViolationException("forced budget create conflict"))
                .when(budgets).saveAndFlush(Mockito.any(Budget.class));

        mvc.perform(post("/api/budgets").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"periodMonth\":\"2027-04\",\"scopeType\":\"TOTAL\",\"amount\":\"100.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("预算关联的数据已变化，请刷新后重试"));

    }

    @Test
    void updateTranslatesDatabaseIntegrityConflictAndRollsBackRevision() throws Exception {
        MockHttpSession session = login();
        MvcResult created = mvc.perform(post("/api/budgets").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"periodMonth\":\"2027-05\",\"scopeType\":\"TOTAL\",\"amount\":\"100.00\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        int version = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("version").asInt();
        Mockito.doThrow(new DataIntegrityViolationException("forced budget update conflict"))
                .when(budgets).flush();

        mvc.perform(patch("/api/budgets/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"version\":" + version + ",\"amount\":\"125.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("预算关联的数据已变化，请刷新后重试"));

        Budget unchanged = budgets.findById(id).orElseThrow();
        assertThat(unchanged.getAmountCents()).isEqualTo(10000L);
        assertThat(unchanged.getVersion()).isEqualTo(version);
        assertThat(revisions.count()).isZero();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
