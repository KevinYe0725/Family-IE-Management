package com.familyfinance.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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
class CategoryHierarchyApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired HouseholdRepository households;
    @Autowired CategoryRepository categories;
    @Autowired FamilyMemberRepository members;
    @Autowired FinancialAccountRepository accounts;
    @Autowired AppUserRepository users;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsOneChildButRejectsGrandchildKindMismatchAndSelfParent() throws Exception {
        MockHttpSession session = login();
        long shopping = createCategory(session, "expense", unique("购物"), null);
        long clothing = createCategory(session, "expense", unique("服饰"), shopping);

        MvcResult listed = mvc.perform(get("/api/categories").session(session).param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode clothingNode = findById(
                objectMapper.readTree(listed.getResponse().getContentAsString()).path("data"), clothing);
        assertThat(clothingNode.path("parentId").asLong()).isEqualTo(shopping);
        assertThat(clothingNode.path("level").asInt()).isEqualTo(2);
        assertThat(clothingNode.path("children")).isEmpty();

        createCategoryResult(session, "expense", unique("外套"), clothing)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.parentId").exists());
        createCategoryResult(session, "income", unique("错误收入"), shopping)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.parentId").exists());

        mvc.perform(patch("/api/categories/{id}", shopping)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("expense", unique("购物更新"), shopping)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.parentId").exists());
    }

    @Test
    void movesLeafBetweenCompatibleParentsButDoesNotTurnExistingParentIntoChild() throws Exception {
        MockHttpSession session = login();
        long firstParent = createCategory(session, "expense", unique("第一父类"), null);
        long secondParent = createCategory(session, "expense", unique("第二父类"), null);
        long leaf = createCategory(session, "expense", unique("叶子"), firstParent);

        mvc.perform(patch("/api/categories/{id}", leaf)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("expense", unique("移动叶子"), secondParent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(secondParent))
                .andExpect(jsonPath("$.data.level").value(2));

        mvc.perform(patch("/api/categories/{id}", secondParent)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("expense", unique("不可下移"), firstParent)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.parentId").exists());
    }

    @Test
    void flatAndTreeListsAreStableBoundedAndHouseholdScoped() throws Exception {
        MockHttpSession session = login();
        long root = createCategory(session, "expense", unique("树根"), null);
        long firstChild = createCategory(session, "expense", unique("孩子一"), root);
        long secondChild = createCategory(session, "expense", unique("孩子二"), root);
        Household outsider = households.save(new Household(unique("外部家庭"), TEST_TIME));
        Category outsiderCategory = categories.saveAndFlush(
                new Category(outsider, TransactionKind.EXPENSE, unique("外部分号"), "#123456", false, TEST_TIME));
        Household household = currentHousehold();
        List<Category> bulk = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            bulk.add(new Category(
                    household,
                    TransactionKind.EXPENSE,
                    "分页分类" + index + Long.toUnsignedString(System.nanoTime(), 36),
                    "#123456",
                    false,
                    TEST_TIME));
        }
        categories.saveAllAndFlush(bulk);

        MvcResult flat = mvc.perform(get("/api/categories").session(session)
                        .param("page", "-3").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().exists("X-Total-Elements"))
                .andExpect(header().exists("X-Total-Pages"))
                .andExpect(header().exists("X-Has-Next"))
                .andExpect(jsonPath("$.data.length()").value(50))
                .andReturn();
        JsonNode flatData = objectMapper.readTree(flat.getResponse().getContentAsString()).path("data");
        List<Long> ids = new ArrayList<>();
        flatData.forEach(row -> ids.add(row.path("id").asLong()));
        assertThat(ids).isSorted().contains(root, firstChild, secondChild).doesNotContain(outsiderCategory.getId());
        MvcResult secondPage = mvc.perform(get("/api/categories").session(session)
                        .param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "1"))
                .andReturn();
        List<Long> secondPageIds = ids(objectMapper.readTree(secondPage.getResponse().getContentAsString()).path("data"));
        assertThat(secondPageIds).isSorted().allMatch(id -> id > ids.get(ids.size() - 1));

        MvcResult tree = mvc.perform(get("/api/categories").session(session)
                        .param("projection", "tree").param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rootNode = findById(objectMapper.readTree(tree.getResponse().getContentAsString()).path("data"), root);
        assertThat(rootNode.path("parentId").isNull()).isTrue();
        assertThat(rootNode.path("level").asInt()).isEqualTo(1);
        assertThat(ids(rootNode.path("children"))).containsExactly(firstChild, secondChild);
        assertThat(rootNode.toString()).doesNotContain(outsiderCategory.getName());

        mvc.perform(get("/api/categories").session(session).param("projection", "graph"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.projection").exists());
    }

    @Test
    void foreignAndUnknownParentsHaveTheSameNonLeakingError() throws Exception {
        MockHttpSession session = login();
        Household outsider = households.save(new Household(unique("隔离家庭"), TEST_TIME));
        Category outsiderParent = categories.saveAndFlush(
                new Category(outsider, TransactionKind.EXPENSE, unique("外部父类"), "#123456", false, TEST_TIME));

        MvcResult foreign = createCategoryResult(session, "expense", unique("外部孩子"), outsiderParent.getId())
                .andExpect(status().isUnprocessableEntity()).andReturn();
        MvcResult unknown = createCategoryResult(session, "expense", unique("未知孩子"), Long.MAX_VALUE)
                .andExpect(status().isUnprocessableEntity()).andReturn();

        JsonNode foreignError = objectMapper.readTree(foreign.getResponse().getContentAsString()).path("error");
        JsonNode unknownError = objectMapper.readTree(unknown.getResponse().getContentAsString()).path("error");
        assertThat(foreignError.path("code").asText()).isEqualTo(unknownError.path("code").asText());
        assertThat(foreignError.path("fields").path("parentId").asText())
                .isEqualTo(unknownError.path("fields").path("parentId").asText());
    }

    @Test
    void transactionsAcceptEitherLevelAndProjectCategoryHierarchy() throws Exception {
        MockHttpSession session = login();
        long parent = createCategory(session, "expense", unique("日常"), null);
        long child = createCategory(session, "expense", unique("早餐"), parent);
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(currentHousehold().getId())
                .orElseThrow().getId();
        long memberId = members.findByHouseholdOrderById(currentHousehold()).get(0).getId();

        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType("application/json")
                        .content(transactionBody(accountId, memberId, parent, "11.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryId").value(parent))
                .andExpect(jsonPath("$.data.categoryParentId").isEmpty())
                .andExpect(jsonPath("$.data.categoryLevel").value(1));
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType("application/json")
                        .content(transactionBody(accountId, memberId, child, "22.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryId").value(child))
                .andExpect(jsonPath("$.data.categoryParentId").value(parent))
                .andExpect(jsonPath("$.data.categoryLevel").value(2));
    }

    @Test
    void dashboardRollsChildrenIntoParentOnlyWhenExplicitlyRequested() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category parent = categories.saveAndFlush(new Category(
                household, TransactionKind.EXPENSE, unique("餐食"), "#123456", false, TEST_TIME));
        Category child = categories.saveAndFlush(new Category(
                household, TransactionKind.EXPENSE, unique("早餐"), "#654321", false, parent, TEST_TIME));
        transactions.saveAndFlush(TransactionTestFixtures.newTransaction(
                accounts, users, household, member, child, TransactionKind.EXPENSE, 2500L,
                LocalDate.parse("2026-10-08"), null, null, unique("rollup"), TEST_TIME, TEST_TIME));

        mvc.perform(get("/api/dashboard").session(session).param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenseByCategory[0].categoryId").value(child.getId()))
                .andExpect(jsonPath("$.data.expenseByCategory[0].categoryName").value(child.getName()));

        mvc.perform(get("/api/dashboard").session(session)
                        .param("month", "2026-10").param("rollupCategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.expense").value("25.00"))
                .andExpect(jsonPath("$.data.expenseByCategory.length()").value(1))
                .andExpect(jsonPath("$.data.expenseByCategory[0].categoryId").value(parent.getId()))
                .andExpect(jsonPath("$.data.expenseByCategory[0].categoryName").value(parent.getName()))
                .andExpect(jsonPath("$.data.expenseByCategory[0].amount").value("25.00"));

        MvcResult analysis = mvc.perform(get("/api/analysis").session(session)
                        .param("month", "2026-10").param("rollupCategories", "true"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode topCategory = null;
        for (JsonNode insight : objectMapper.readTree(analysis.getResponse().getContentAsString())
                .path("data").path("insights")) {
            if ("TOP_CATEGORY".equals(insight.path("type").asText())) topCategory = insight;
        }
        assertThat(topCategory).isNotNull();
        assertThat(topCategory.path("message").asText()).contains(parent.getName());
    }

    @Test
    void deleteExplicitlyBlocksChildrenTransactionsBudgetsAndEveryRecurringRule() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        long userId = users.findByEmail("demo@local.family").orElseThrow().getId();
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(household.getId())
                .orElseThrow().getId();

        long withChild = createCategory(session, "expense", unique("有孩子"), null);
        createCategory(session, "expense", unique("孩子"), withChild);
        assertDeleteBlocked(session, withChild, "子分类");

        Category withTransaction = categories.saveAndFlush(new Category(
                household, TransactionKind.EXPENSE, unique("有流水"), "#123456", false, TEST_TIME));
        transactions.saveAndFlush(TransactionTestFixtures.newTransaction(
                accounts, users, household, member, withTransaction, TransactionKind.EXPENSE, 100L,
                LocalDate.parse("2026-09-03"), null, null, unique("tx"), TEST_TIME, TEST_TIME));
        assertDeleteBlocked(session, withTransaction.getId(), "收支记录");

        long withBudget = createCategory(session, "expense", unique("有预算"), null);
        jdbc.update("insert into budgets (household_id,period_month,scope_type,category_id,amount_cents,version,active) values (?,?,?,?,?,?,?)",
                household.getId(), "2026-09", "CATEGORY", withBudget, 10000L, 1, true);
        assertDeleteBlocked(session, withBudget, "预算");

        for (boolean active : List.of(true, false)) {
            long withRule = createCategory(session, "expense", unique(active ? "有效规则" : "历史规则"), null);
            jdbc.update("insert into recurring_rules (household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,account_id,member_id,category_id,active,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    household.getId(), "EXPENSE", 100L, "MONTHLY", 1, 1, LocalDate.parse("2026-10-01"),
                    accountId, member.getId(), withRule, active, userId);
            assertDeleteBlocked(session, withRule, "周期规则");
        }
    }

    @Test
    void kindChangeIsBlockedByCurrentCategoryBudgetButOtherEditsRemainAllowed() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        long compatibleParentId = createCategory(session, "expense", unique("兼容父类"), null);
        long categoryId = createCategory(session, "expense", unique("当前预算分类"), null);
        jdbc.update("insert into budgets "
                        + "(household_id,period_month,scope_type,category_id,amount_cents,version,active) "
                        + "values (?,?,?,?,?,?,?)",
                household.getId(), "2026-09", "CATEGORY", categoryId, 10000L, 1, true);

        mvc.perform(patch("/api/categories/{id}", categoryId)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("income", unique("禁止改类型"), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("预算")));

        mvc.perform(patch("/api/categories/{id}", categoryId)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("expense", unique("允许改名和移动"), compatibleParentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("expense"))
                .andExpect(jsonPath("$.data.parentId").value(compatibleParentId));
    }

    @Test
    void kindChangeIsBlockedWhenOnlyImmutableBudgetRevisionReferencesCategory() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        long categoryId = createCategory(session, "expense", unique("历史预算分类"), null);
        long userId = users.findByEmail("demo@local.family").orElseThrow().getId();
        jdbc.update("insert into budgets "
                        + "(household_id,period_month,scope_type,amount_cents,version,active) "
                        + "values (?,?,?,?,?,?)",
                household.getId(), "2026-10", "TOTAL", 20000L, 2, true);
        long budgetId = jdbc.queryForObject(
                "select id from budgets where household_id=? and period_month='2026-10' and scope_type='TOTAL'",
                Long.class,
                household.getId());
        jdbc.update("insert into budget_revisions "
                        + "(household_id,budget_id,old_amount_cents,new_amount_cents,changed_by,changed_at,"
                        + "old_period_month,new_period_month,old_scope_type,new_scope_type,old_category_id,"
                        + "new_category_id,old_member_id,new_member_id,old_active,new_active) "
                        + "values (?,?,?,?,?,current_timestamp,?,?,?,?,?,?,?,?,?,?)",
                household.getId(), budgetId, 10000L, 20000L, userId,
                "2026-10", "2026-10", "CATEGORY", "TOTAL", categoryId,
                null, null, null, true, true);

        mvc.perform(patch("/api/categories/{id}", categoryId)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("income", unique("历史禁止改类型"), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("预算")));
    }

    @Test
    void kindChangeIsBlockedByAnArchivedRecurringRuleReference() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        long categoryId = createCategory(session, "expense", unique("历史周期分类"), null);
        long userId = users.findByEmail("demo@local.family").orElseThrow().getId();
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(household.getId())
                .orElseThrow().getId();
        jdbc.update("insert into recurring_rules "
                        + "(household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,"
                        + "account_id,member_id,category_id,active,created_by,assigned_user_id,paused) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                household.getId(), "EXPENSE", 100L, "MONTHLY", 1, 1, null,
                accountId, member.getId(), categoryId, false, userId, userId, false);

        mvc.perform(patch("/api/categories/{id}", categoryId)
                        .session(session).with(csrf()).contentType("application/json")
                        .content(categoryBody("income", unique("周期禁止改类型"), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("周期规则")));
    }

    private void assertDeleteBlocked(MockHttpSession session, long categoryId, String messagePart) throws Exception {
        mvc.perform(delete("/api/categories/{id}", categoryId).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString(messagePart)));
    }

    private long createCategory(MockHttpSession session, String kind, String name, Long parentId) throws Exception {
        return readId(createCategoryResult(session, kind, name, parentId)
                .andExpect(status().isCreated()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions createCategoryResult(
            MockHttpSession session, String kind, String name, Long parentId) throws Exception {
        return mvc.perform(post("/api/categories").session(session).with(csrf()).contentType("application/json")
                .content(categoryBody(kind, name, parentId)));
    }

    private String categoryBody(String kind, String name, Long parentId) {
        return """
                {"kind":"%s","name":"%s","color":"#123456","parentId":%s}
                """.formatted(kind, name, parentId == null ? "null" : parentId);
    }

    private String transactionBody(long accountId, long memberId, long categoryId, String amount) {
        return """
                {"kind":"expense","amount":"%s","occurredOn":"2026-10-03","accountId":%d,
                 "memberId":%d,"categoryId":%d,"note":"hierarchy-transaction"}
                """.formatted(amount, accountId, memberId, categoryId);
    }

    private Household currentHousehold() {
        return users.findByEmail("demo@local.family").orElseThrow().getHousehold();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private static JsonNode findById(JsonNode rows, long id) {
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == id) return row;
        }
        throw new AssertionError("Missing category " + id + " in " + rows);
    }

    private static List<Long> ids(JsonNode rows) {
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private static String unique(String prefix) {
        return prefix + Long.toUnsignedString(System.nanoTime(), 36);
    }
}
