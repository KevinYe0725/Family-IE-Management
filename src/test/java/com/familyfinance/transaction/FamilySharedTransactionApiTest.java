package com.familyfinance.transaction;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Transactional
class FamilySharedTransactionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired CategoryRepository categories;

    @Test
    void familySharedExpensesRoundTripThroughCreateListEditAndBudgets() throws Exception {
        MockHttpSession session = login();
        Household household = users.findByEmail("demo@local.family").orElseThrow().getHousehold();
        long accountId = accountId(session);
        Category expense = categories.findAll().stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE
                        && category.getHousehold().getId().equals(household.getId()))
                .findFirst().orElseThrow();
        long memberId = householdMembers(session);
        long expenseCategoryId = expense.getId();

        MvcResult created = mvc.perform(post("/api/transactions").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"kind":"expense","amount":"5.00","occurredOn":"2026-09-05",
                                 "accountId":%d,"memberId":null,"categoryId":%d}
                                """.formatted(accountId, expenseCategoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberId").isEmpty())
                .andExpect(jsonPath("$.data.memberName").value("全体（家庭共同）"))
                .andReturn();
        long transactionId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        String csv=mvc.perform(get("/api/export.csv").session(session).param("month","2026-09").param("memberId","0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(csv).contains("全体（家庭共同）");

        mvc.perform(get("/api/transactions").session(session)
                        .param("month", "2026-09").param("memberId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(transactionId)).exists());

        // Editing may still attribute the expense to a concrete member later.
        mvc.perform(patch("/api/transactions/{id}", transactionId).session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"memberId\":" + memberId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(memberId));
        mvc.perform(patch("/api/transactions/{id}", transactionId).session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"memberId\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").isEmpty())
                .andExpect(jsonPath("$.data.memberName").value("全体（家庭共同）"));
    }

    private long accountId(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/accounts").session(session).param("size", "50"))
                .andExpect(status().isOk()).andReturn();
        JsonNode accounts = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("items");
        return accounts.get(0).path("id").asLong();
    }

    private long householdMembers(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/members").session(session))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").get(0).path("id").asLong();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
