package com.familyfinance.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.familyfinance.household.HouseholdRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
class BudgetApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired HouseholdRepository households;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;

    @Test
    void ownerSetsAndReadsMonthlyTotalBudgetAndCannotCreateLegacyTotalRow() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/budgets").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"periodMonth":"2026-09","scopeType":"TOTAL","amount":"1000.00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.scopeType").exists());

        mvc.perform(put("/api/budgets/total").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"periodMonth":"2026-09","amount":"1000.00","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodMonth").value("2026-09"))
                .andExpect(jsonPath("$.data.amount").value("1000.00"))
                .andExpect(jsonPath("$.data.version").value(0));

        mvc.perform(get("/api/budgets/total").session(session).param("periodMonth", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value("1000.00"));
    }

    @Test
    void validatesMonthAmountAndScopeTargetsWithoutLeakingForeignResources() throws Exception {
        MockHttpSession session = login();
        Household current = currentHousehold();
        Category expense = category(current, TransactionKind.EXPENSE, "预算餐饮");
        Category income = category(current, TransactionKind.INCOME, "预算工资");
        FamilyMember member = members.findByHouseholdOrderById(current).get(0);
        Household foreign = households.saveAndFlush(new Household(unique("外部家庭"), Instant.parse("2026-09-03T00:00:00Z")));
        Category foreignCategory = category(foreign, TransactionKind.EXPENSE, "外部分类");
        FamilyMember foreignMember = members.saveAndFlush(new FamilyMember(
                foreign, "外部成员", "成员", Instant.parse("2026-09-03T00:00:00Z")));

        assertValidation(session, categoryBody("2026-9", expense.getId(), "100.00"), "periodMonth");
        assertValidation(session, categoryBody("2026-09", expense.getId(), "0.00"), "amount");
        assertValidation(session, categoryBody("2026-09", expense.getId(), "1000000000.00"), "amount");
        assertValidation(session, categoryMemberBody("2026-09", expense.getId(), null, "100.00"), "categoryId");
        assertValidation(session, categoryBody(income.getId(), "100.00"), "categoryId");

        MvcResult foreignCategoryResult = create(session, categoryBody(foreignCategory.getId(), "100.00"))
                .andExpect(status().isBadRequest()).andReturn();
        MvcResult unknownCategoryResult = create(session, categoryBody(Long.MAX_VALUE, "100.00"))
                .andExpect(status().isBadRequest()).andReturn();
        assertSameFieldError(foreignCategoryResult, unknownCategoryResult, "categoryId");

        MvcResult foreignMemberResult = create(session, memberBody(foreignMember.getId(), "100.00"))
                .andExpect(status().isBadRequest()).andReturn();
        MvcResult unknownMemberResult = create(session, memberBody(Long.MAX_VALUE, "100.00"))
                .andExpect(status().isBadRequest()).andReturn();
        assertSameFieldError(foreignMemberResult, unknownMemberResult, "memberId");

        create(session, categoryBody(expense.getId(), "250.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryId").value(expense.getId()));
        create(session, memberBody(member.getId(), "300.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberId").value(member.getId()));
        create(session, categoryMemberBody("2026-09", expense.getId(), member.getId(), "80.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scopeType").value("CATEGORY_MEMBER"))
                .andExpect(jsonPath("$.data.categoryId").value(expense.getId()))
                .andExpect(jsonPath("$.data.memberId").value(member.getId()));
    }

    @Test
    void enforcesOneActiveBudgetPerMonthAndScopeShapeButAllowsReplacementAfterDeactivation() throws Exception {
        MockHttpSession session = login();
        Household current = currentHousehold();
        Category food = category(current, TransactionKind.EXPENSE, "互斥餐饮");
        long id = createId(session, categoryBody("2026-10", food.getId(), "100.00"));
        int version = getBudget(session, id).path("version").asInt();

        create(session, categoryBody("2026-10", food.getId(), "200.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));

        mvc.perform(patch("/api/budgets/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"version":%d,"active":false}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        create(session, categoryBody("2026-10", food.getId(), "200.00"))
                .andExpect(status().isCreated());
    }

    @Test
    void memberCategoryRowsAreRejectedWhenPoolWouldExceedMonthlyTotalAndTotalCannotShrinkBelowPool() throws Exception {
        MockHttpSession session = login();
        Household current = currentHousehold();
        Category food = category(current, TransactionKind.EXPENSE, "池餐饮");
        Category travel = category(current, TransactionKind.EXPENSE, "池旅行");

        mvc.perform(put("/api/budgets/total").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"periodMonth":"2026-11","amount":"1000.00","version":0}
                                """))
                .andExpect(status().isOk());

        create(session, categoryBody("2026-11", food.getId(), "600.00"))
                .andExpect(status().isCreated());
        assertValidation(session, categoryBody("2026-11", travel.getId(), "500.00"), "amount");
        create(session, categoryBody("2026-11", travel.getId(), "400.00"))
                .andExpect(status().isCreated());

        // Member-tagged rows are observation lines and never hit the pool.
        FamilyMember member = members.findByHouseholdOrderById(current).get(0);
        create(session, categoryMemberBody("2026-11", food.getId(), member.getId(), "20000.00"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/budgets/total").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"periodMonth":"2026-11","amount":"999.00","version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOTAL_BELOW_ALLOCATED"));
    }

    @Test
    void patchWritesCompleteImmutableRevisionSnapshotsAndRejectsStaleVersion() throws Exception {
        MockHttpSession session = login();
        Household current = currentHousehold();
        Category food = category(current, TransactionKind.EXPENSE, "修订餐饮");
        FamilyMember member = members.findByHouseholdOrderById(current).get(0);
        long id = createId(session, categoryBody(food.getId(), "1000.00"));
        JsonNode before = getBudget(session, id);

        MvcResult updated = mvc.perform(patch("/api/budgets/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"version":%d,"periodMonth":"2026-10","scopeType":"CATEGORY_MEMBER",
                                 "categoryId":%d,"memberId":%d,"amount":"1200.00","active":false,
                                 "note":"孩子开学季"}
                                """.formatted(before.path("version").asInt(), food.getId(), member.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodMonth").value("2026-10"))
                .andExpect(jsonPath("$.data.scopeType").value("CATEGORY_MEMBER"))
                .andExpect(jsonPath("$.data.categoryId").value(food.getId()))
                .andExpect(jsonPath("$.data.memberId").value(member.getId()))
                .andExpect(jsonPath("$.data.amount").value("1200.00"))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.note").value("孩子开学季"))
                .andReturn();
        int newVersion = objectMapper.readTree(updated.getResponse().getContentAsString())
                .path("data").path("version").asInt();
        assertThat(newVersion).isGreaterThan(before.path("version").asInt());

        mvc.perform(get("/api/budgets/{id}/revisions", id).session(session)
                        .param("page", "-2").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].budgetId").value(id))
                .andExpect(jsonPath("$.data[0].oldPeriodMonth").value("2026-09"))
                .andExpect(jsonPath("$.data[0].newPeriodMonth").value("2026-10"))
                .andExpect(jsonPath("$.data[0].oldScopeType").value("CATEGORY"))
                .andExpect(jsonPath("$.data[0].newScopeType").value("CATEGORY_MEMBER"))
                .andExpect(jsonPath("$.data[0].oldCategoryId").value(food.getId()))
                .andExpect(jsonPath("$.data[0].newCategoryId").value(food.getId()))
                .andExpect(jsonPath("$.data[0].oldMemberId").isEmpty())
                .andExpect(jsonPath("$.data[0].newMemberId").value(member.getId()))
                .andExpect(jsonPath("$.data[0].oldAmount").value("1000.00"))
                .andExpect(jsonPath("$.data[0].newAmount").value("1200.00"))
                .andExpect(jsonPath("$.data[0].oldActive").value(true))
                .andExpect(jsonPath("$.data[0].newActive").value(false))
                .andExpect(jsonPath("$.data[0].oldNote").isEmpty())
                .andExpect(jsonPath("$.data[0].newNote").value("孩子开学季"))
                .andExpect(jsonPath("$.data[0].changedByUserId").isNumber())
                .andExpect(jsonPath("$.data[0].changedAt").isNotEmpty());

        mvc.perform(patch("/api/budgets/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"version":%d,"amount":"1300.00"}
                                """.formatted(before.path("version").asInt())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_VERSION"));

        mvc.perform(patch("/api/budgets/{id}/revisions/1", id).session(session).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/categories/{id}", food.getId()).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void memberCanReadStableBoundedHouseholdListsAndHistoryButCannotMutate() throws Exception {
        MockHttpSession owner = login();
        MockHttpSession member = join(owner, unique("budget-member") + "@example.com", HouseholdRole.MEMBER);
        Household current = currentHousehold();
        Category food = category(current, TransactionKind.EXPENSE, "只读餐饮");
        long id = createId(owner, categoryBody("2026-11", food.getId(), "100.00"));
        int version = getBudget(owner, id).path("version").asInt();
        mvc.perform(patch("/api/budgets/{id}", id).session(owner).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"version":%d,"amount":"110.00"}
                                """.formatted(version)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/budgets").session(member)
                        .param("periodMonth", "2026-11").param("page", "-1").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "1"))
                .andExpect(header().string("X-Total-Pages", "1"))
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data[0].id").value(id));
        mvc.perform(get("/api/budgets/{id}", id).session(member))
                .andExpect(status().isOk());
        mvc.perform(get("/api/budgets/{id}/revisions", id).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/budgets/usage").session(member).param("periodMonth", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].budget.id").value(id));
        mvc.perform(get("/api/budgets/total").session(member).param("periodMonth", "2026-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").isEmpty());

        create(member, categoryBody("2026-12", food.getId(), "100.00"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/budgets/{id}", id).session(member).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"version":%d,"amount":"120.00"}
                                """.formatted(version + 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignAndUnknownBudgetReadsAndUpdatesAreIndistinguishable() throws Exception {
        MockHttpSession session = login();
        Household foreign = households.saveAndFlush(new Household(unique("隔离家庭"), Instant.parse("2026-09-03T00:00:00Z")));
        Category foreignCategory = category(foreign, TransactionKind.EXPENSE, "隔离分类");
        Budget foreignBudget = new Budget(foreign, java.time.YearMonth.of(2026, 9), BudgetScopeType.CATEGORY,
                foreignCategory, null, 10000L);
        // Persist through the already mapped aggregate so the API boundary is exercised against a real foreign row.
        long foreignId = budgetRepository().saveAndFlush(foreignBudget).getId();

        MvcResult foreignGet = mvc.perform(get("/api/budgets/{id}", foreignId).session(session))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult unknownGet = mvc.perform(get("/api/budgets/{id}", Long.MAX_VALUE).session(session))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(foreignGet.getResponse().getContentAsString())
                .isEqualTo(unknownGet.getResponse().getContentAsString());

        MvcResult foreignPatch = mvc.perform(patch("/api/budgets/{id}", foreignId).session(session).with(csrf())
                        .contentType("application/json").content("{\"version\":1,\"amount\":\"2.00\"}"))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult unknownPatch = mvc.perform(patch("/api/budgets/{id}", Long.MAX_VALUE).session(session).with(csrf())
                        .contentType("application/json").content("{\"version\":1,\"amount\":\"2.00\"}"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(foreignPatch.getResponse().getContentAsString())
                .isEqualTo(unknownPatch.getResponse().getContentAsString());
    }

    @Autowired BudgetRepository injectedBudgetRepository;

    private BudgetRepository budgetRepository() {
        return injectedBudgetRepository;
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
                                {"email":"%s","displayName":"预算成员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private org.springframework.test.web.servlet.ResultActions create(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/api/budgets").session(session).with(csrf())
                .contentType("application/json").content(body));
    }

    private long createId(MockHttpSession session, String body) throws Exception {
        MvcResult result = create(session, body).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private JsonNode getBudget(MockHttpSession session, long id) throws Exception {
        MvcResult result = mvc.perform(get("/api/budgets/{id}", id).session(session))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertValidation(MockHttpSession session, String body, String field) throws Exception {
        create(session, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields." + field).exists());
    }

    private void assertSameFieldError(MvcResult first, MvcResult second, String field) throws Exception {
        JsonNode firstError = objectMapper.readTree(first.getResponse().getContentAsString()).path("error");
        JsonNode secondError = objectMapper.readTree(second.getResponse().getContentAsString()).path("error");
        assertThat(firstError.path("code").asText()).isEqualTo(secondError.path("code").asText());
        assertThat(firstError.path("fields").path(field).asText())
                .isEqualTo(secondError.path("fields").path(field).asText());
    }

    private Household currentHousehold() {
        return users.findByEmail("demo@local.family").orElseThrow().getHousehold();
    }

    private Category category(Household household, TransactionKind kind, String prefix) {
        return categories.saveAndFlush(new Category(
                household, kind, unique(prefix), "#123456", false, Instant.parse("2026-09-03T00:00:00Z")));
    }

    private static String categoryBody(String month, long categoryId, String amount) {
        return """
                {"periodMonth":"%s","scopeType":"CATEGORY","categoryId":%d,"amount":"%s"}
                """.formatted(month, categoryId, amount);
    }

    private static String categoryBody(long categoryId, String amount) {
        return categoryBody("2026-09", categoryId, amount);
    }

    private static String memberBody(long memberId, String amount) {
        return """
                {"periodMonth":"2026-09","scopeType":"MEMBER","memberId":%d,"amount":"%s"}
                """.formatted(memberId, amount);
    }

    private static String categoryMemberBody(String month, Long categoryId, Long memberId, String amount) {
        return """
                {"periodMonth":"%s","scopeType":"CATEGORY_MEMBER","categoryId":%s,"memberId":%s,"amount":"%s"}
                """.formatted(month,
                categoryId == null ? "null" : categoryId,
                memberId == null ? "null" : memberId,
                amount);
    }

    private static String unique(String prefix) {
        return prefix + Long.toUnsignedString(System.nanoTime(), 36);
    }
}
