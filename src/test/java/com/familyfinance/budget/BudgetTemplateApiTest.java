package com.familyfinance.budget;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
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
class BudgetTemplateApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired BudgetRepository budgetRepository;

    @Test
    void createApplySkipsDuplicatesAndDeleteRoundTrip() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        Category food = category(household, TransactionKind.EXPENSE, "模板餐饮");
        FamilyMember first = members.findByHouseholdOrderById(household).get(0);

        MvcResult created = mvc.perform(post("/api/budget-templates").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"日常月份","rows":[
                                  {"scopeType":"CATEGORY","categoryId":%d,"amount":"1000.00","note":"日常餐饮"},
                                  {"scopeType":"CATEGORY_MEMBER","categoryId":%d,"memberId":%d,"amount":"300.00"}
                                ]}
                                """.formatted(food.getId(), food.getId(), first.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("日常月份"))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andReturn();
        long templateId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mvc.perform(get("/api/budget-templates").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(templateId));

        mvc.perform(post("/api/budget-templates/{id}/apply", templateId).session(session).with(csrf())
                        .param("periodMonth", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.copied").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0));
        mvc.perform(post("/api/budget-templates/{id}/apply", templateId).session(session).with(csrf())
                        .param("periodMonth", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.copied").value(0))
                .andExpect(jsonPath("$.data.skipped").value(2));
        assertThat(monthRows("2026-10", household), 2L);
        mvc.perform(get("/api/budgets").session(session).param("periodMonth", "2026-10"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.note=='日常餐饮')]").exists());

        mvc.perform(delete("/api/budget-templates/{id}", templateId).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/budget-templates").session(session))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsInvalidTemplateRowsAndDuplicateNames() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        Category food = category(household, TransactionKind.EXPENSE, "模板校验餐饮");
        mvc.perform(post("/api/budget-templates").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"坏模板","rows":[
                                  {"scopeType":"TOTAL","amount":"1.00"},
                                  {"scopeType":"CATEGORY_MEMBER","categoryId":%d,"amount":"1.00"}
                                ]}
                                """.formatted(food.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields['rows[0].scopeType']").exists())
                .andExpect(jsonPath("$.error.fields['rows[1].categoryId']").exists());
        mvc.perform(post("/api/budget-templates").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"同名校验\",\"rows\":[{\"scopeType\":\"CATEGORY\",\"categoryId\":%d,\"amount\":\"1.00\"}]}"
                                .formatted(food.getId())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/budget-templates").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"同名校验\",\"rows\":[{\"scopeType\":\"CATEGORY\",\"categoryId\":%d,\"amount\":\"2.00\"}]}"
                                .formatted(food.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void memberCannotManageTemplates() throws Exception {
        MockHttpSession owner = login();
        MockHttpSession member = join(owner, "template-member-" + System.nanoTime() + "@example.com", HouseholdRole.MEMBER);
        mvc.perform(post("/api/budget-templates").session(member).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"越权模板\",\"rows\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/budget-templates").session(member))
                .andExpect(status().isOk());
    }

    private void assertThat(long actual, long expected) {
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }

    private long monthRows(String month, Household household) {
        return budgetRepository.findAll().stream()
                .filter(budget -> budget.getHousehold().getId().equals(household.getId()))
                .filter(budget -> budget.getPeriodMonth().toString().equals(month))
                .count();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType("application/json").content("{\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
                                {"email":"%s","displayName":"模板成员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private Household currentHousehold() {
        return users.findByEmail("demo@local.family").orElseThrow().getHousehold();
    }

    private Category category(Household household, TransactionKind kind, String prefix) {
        return categories.saveAndFlush(new Category(
                household, kind, prefix + System.nanoTime(), "#123456", false, TEST_TIME));
    }
}
