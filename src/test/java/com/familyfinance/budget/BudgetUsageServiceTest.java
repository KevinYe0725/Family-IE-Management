package com.familyfinance.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
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
class BudgetUsageServiceTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired HouseholdRepository households;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired FinancialAccountRepository accounts;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;
    private String currentEmail;

    @Test
    void usageCountsOnlyExpenseTransactionsInMonthForTotalCategoryAndMemberScopes() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember first = members.findByHouseholdOrderById(household).get(0);
        FamilyMember second = members.saveAndFlush(new FamilyMember(household, "预算第二成员", "成员", TEST_TIME));
        Category parent = category(household, TransactionKind.EXPENSE, "预算父分类", null);
        Category child = category(household, TransactionKind.EXPENSE, "预算子分类", parent);
        Category otherExpense = category(household, TransactionKind.EXPENSE, "预算其他支出", null);
        Category income = category(household, TransactionKind.INCOME, "预算收入", null);

        long totalBudget = createBudget(session, """
                {"periodMonth":"2026-09","scopeType":"TOTAL","amount":"1000.00"}
                """);
        long categoryBudget = createBudget(session, """
                {"periodMonth":"2026-09","scopeType":"CATEGORY","categoryId":%d,"amount":"50.00"}
                """.formatted(parent.getId()));
        long memberBudget = createBudget(session, """
                {"periodMonth":"2026-09","scopeType":"MEMBER","memberId":%d,"amount":"200.00"}
                """.formatted(first.getId()));

        transaction(household, first, parent, TransactionKind.EXPENSE, 3500L, "2026-09-02");
        transaction(household, first, child, TransactionKind.EXPENSE, 2500L, "2026-09-03");
        transaction(household, second, otherExpense, TransactionKind.EXPENSE, 2000L, "2026-09-04");
        transaction(household, first, income, TransactionKind.INCOME, 100_000L, "2026-09-05");
        transaction(household, first, parent, TransactionKind.EXPENSE, 999_00L, "2026-10-01");
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(household.getId())
                .orElseThrow().getId();
        long userId = users.findByEmail(currentEmail).orElseThrow().getId();
        jdbc.update("""
                insert into recurring_rules
                (household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,
                 account_id,member_id,category_id,active,created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, household.getId(), "EXPENSE", 9_999_999L, "MONTHLY", 1, 9,
                LocalDate.parse("2026-09-09"), accountId, first.getId(), parent.getId(), true, userId);
        long ruleId = jdbc.queryForObject("select max(id) from recurring_rules", Long.class);
        jdbc.update("""
                insert into recurring_occurrences
                (household_id,rule_id,due_on,status,confirmed_transaction_id,assigned_user_id)
                values (?,?,?,?,?,?)
                """, household.getId(), ruleId, LocalDate.parse("2026-09-09"), "PENDING", null, userId);
        long occurrenceId = jdbc.queryForObject("select max(id) from recurring_occurrences", Long.class);
        jdbc.update("""
                insert into financial_transactions
                (household_id,member_id,account_id,created_by_user_id,category_id,kind,amount_cents,
                 occurred_on,note,created_at,updated_at,source_type,source_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, household.getId(), first.getId(), accountId, userId, parent.getId(), "EXPENSE", 777L,
                LocalDate.parse("2026-09-09"), "unconfirmed recurring sentinel", TEST_TIME, TEST_TIME,
                "RECURRING", occurrenceId);

        JsonNode exact = usage(session, false);
        assertUsage(find(exact, totalBudget), "80.00", "920.00", "8.00", "ON_TRACK", false);
        assertUsage(find(exact, categoryBudget), "35.00", "15.00", "70.00", "ON_TRACK", false);
        assertUsage(find(exact, memberBudget), "60.00", "140.00", "30.00", "ON_TRACK", false);

        JsonNode rolled = usage(session, true);
        assertUsage(find(rolled, categoryBudget), "60.00", "-10.00", "120.00", "OVER_BUDGET", true);
    }

    @Test
    void percentUsesDefinedDecimalRoundingAndCanExceedOneHundred() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category category = category(household, TransactionKind.EXPENSE, "精确比例", null);
        long budget = createBudget(session, """
                {"periodMonth":"2026-09","scopeType":"CATEGORY","categoryId":%d,"amount":"3.00"}
                """.formatted(category.getId()));
        transaction(household, member, category, TransactionKind.EXPENSE, 100L, "2026-09-02");

        assertUsage(find(usage(session, false), budget),
                "1.00", "2.00", "33.33", "ON_TRACK", false);
        transaction(household, member, category, TransactionKind.EXPENSE, 250L, "2026-09-03");
        assertUsage(find(usage(session, false), budget),
                "3.50", "-0.50", "116.67", "OVER_BUDGET", false);
    }

    @Test
    void statusUsesExactAmountsAtJustOverEqualJustBelowAndEightyPercentBoundaries() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category justOver = category(household, TransactionKind.EXPENSE, "刚超预算", null);
        Category equal = category(household, TransactionKind.EXPENSE, "正好用完", null);
        Category justBelowEighty = category(household, TransactionKind.EXPENSE, "略低八成", null);
        Category atEighty = category(household, TransactionKind.EXPENSE, "正好八成", null);
        Category longBoundary = category(household, TransactionKind.EXPENSE, "长整型边界", null);
        long justOverBudget = createBudget(session, categoryBudgetBody(justOver.getId(), "1000.00"));
        long equalBudget = createBudget(session, categoryBudgetBody(equal.getId(), "1000.00"));
        long justBelowBudget = createBudget(session, categoryBudgetBody(justBelowEighty.getId(), "200.01"));
        long atEightyBudget = createBudget(session, categoryBudgetBody(atEighty.getId(), "200.00"));
        long longBoundaryBudget = createBudget(
                session, categoryBudgetBody(longBoundary.getId(), "999999999.99"));
        transaction(household, member, justOver, TransactionKind.EXPENSE, 100_001L, "2026-09-02");
        transaction(household, member, equal, TransactionKind.EXPENSE, 100_000L, "2026-09-02");
        transaction(household, member, justBelowEighty, TransactionKind.EXPENSE, 16_000L, "2026-09-02");
        transaction(household, member, atEighty, TransactionKind.EXPENSE, 16_000L, "2026-09-02");
        transaction(household, member, longBoundary, TransactionKind.EXPENSE, Long.MAX_VALUE, "2026-09-02");

        JsonNode usages = usage(session, false);
        assertThat(find(usages, justOverBudget).path("percent").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(find(usages, justOverBudget).path("status").asText()).isEqualTo("OVER_BUDGET");
        assertThat(find(usages, equalBudget).path("status").asText()).isEqualTo("AT_LIMIT");
        assertThat(find(usages, justBelowBudget).path("percent").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(find(usages, justBelowBudget).path("status").asText()).isEqualTo("ON_TRACK");
        assertThat(find(usages, atEightyBudget).path("status").asText()).isEqualTo("NEAR_LIMIT");
        assertThat(find(usages, longBoundaryBudget).path("status").asText()).isEqualTo("OVER_BUDGET");
        assertThat(BudgetUsageService.status(Long.MAX_VALUE - 1, Long.MAX_VALUE))
                .isEqualTo(BudgetUsageStatus.NEAR_LIMIT);
    }

    @Test
    void inactiveBudgetsAndOtherHouseholdsNeverAppearAndPagesAreBounded() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        long active = createBudget(session, """
                {"periodMonth":"2026-12","scopeType":"TOTAL","amount":"10.00"}
                """);
        int version = budget(session, active).path("version").asInt();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/budgets/{id}", active).session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"version\":" + version + ",\"active\":false}"))
                .andExpect(status().isOk());

        Household foreign = households.saveAndFlush(new Household("usage-foreign-" + System.nanoTime(), TEST_TIME));
        budgetRepository.saveAndFlush(new Budget(foreign, YearMonth.of(2026, 12), BudgetScopeType.TOTAL,
                null, null, 1000L));

        mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2026-12").param("page", "-2").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "0"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void listAndUsagePaginationAreStableBoundedAndConsistent() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        List<Budget> bulk = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            Category category = category(household, TransactionKind.EXPENSE, "分页预算" + index, null);
            bulk.add(new Budget(household, YearMonth.of(2027, 6), BudgetScopeType.CATEGORY,
                    category, null, 10000L));
        }
        budgetRepository.saveAllAndFlush(bulk);

        MvcResult first = mvc.perform(get("/api/budgets").session(session)
                        .param("periodMonth", "2027-06").param("page", "-2").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$.data.length()").value(50)).andReturn();
        MvcResult second = mvc.perform(get("/api/budgets").session(session)
                        .param("periodMonth", "2027-06").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data.length()").value(5)).andReturn();
        List<Long> firstIds = budgetIds(objectMapper.readTree(first.getResponse().getContentAsString()).path("data"));
        List<Long> secondIds = budgetIds(objectMapper.readTree(second.getResponse().getContentAsString()).path("data"));
        assertThat(firstIds).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(secondIds).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

        mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2027-06").param("page", "0").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(jsonPath("$.data.length()").value(50));
        mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2027-06").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    @Test
    void usageRejectsAggregateValuesBeyondSignedLongInsteadOfWrapping() throws Exception {
        MockHttpSession session = login();
        Household household = currentHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category category = category(household, TransactionKind.EXPENSE, "溢出预算", null);
        createBudget(session, """
                {"periodMonth":"2028-01","scopeType":"TOTAL","amount":"1.00"}
                """);
        transaction(household, member, category, TransactionKind.EXPENSE, Long.MAX_VALUE, "2028-01-02");
        transaction(household, member, category, TransactionKind.EXPENSE, 1L, "2028-01-03");

        mvc.perform(get("/api/budgets/usage").session(session).param("periodMonth", "2028-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AMOUNT_OVERFLOW"));
    }

    @Autowired BudgetRepository budgetRepository;

    private JsonNode usage(MockHttpSession session, boolean rollup) throws Exception {
        MvcResult result = mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2026-09")
                        .param("rollupCategories", Boolean.toString(rollup))
                        .param("page", "0").param("size", "50"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode budget(MockHttpSession session, long id) throws Exception {
        MvcResult result = mvc.perform(get("/api/budgets/{id}", id).session(session))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long createBudget(MockHttpSession session, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/budgets").session(session).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void transaction(
            Household household, FamilyMember member, Category category,
            TransactionKind kind, long amountCents, String date) {
        transactions.saveAndFlush(TransactionTestFixtures.newTransaction(
                accounts, users, household, member, category, kind, amountCents,
                LocalDate.parse(date), null, null, "budget-usage-" + System.nanoTime(), TEST_TIME, TEST_TIME));
    }

    private Category category(Household household, TransactionKind kind, String name, Category parent) {
        return categories.saveAndFlush(new Category(
                household, kind, name + System.nanoTime(), "#123456", false, parent, TEST_TIME));
    }

    private MockHttpSession login() throws Exception {
        currentEmail = "usage-" + Long.toUnsignedString(System.nanoTime(), 36) + "@example.com";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
                                {"email":"%s","displayName":"预算所有者","password":"family-pass-2026",
                                 "mode":"CREATE","householdName":"预算测试家庭"}
                                """.formatted(currentEmail)))
                .andExpect(status().isCreated());
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", currentEmail).param("password", "family-pass-2026"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Household currentHousehold() {
        return users.findByEmail(currentEmail).orElseThrow().getHousehold();
    }

    private static JsonNode find(JsonNode usages, long budgetId) {
        for (JsonNode usage : usages) {
            if (usage.path("budget").path("id").asLong() == budgetId) return usage;
        }
        throw new AssertionError("Missing budget usage " + budgetId + " in " + usages);
    }

    private static void assertUsage(
            JsonNode usage, String spent, String remaining, String percent, String status, boolean rollup) {
        assertThat(usage.path("spent").asText()).isEqualTo(spent);
        assertThat(usage.path("remaining").asText()).isEqualTo(remaining);
        assertThat(usage.path("percent").decimalValue()).isEqualByComparingTo(percent);
        assertThat(usage.path("status").asText()).isEqualTo(status);
        assertThat(usage.path("rollupCategories").asBoolean()).isEqualTo(rollup);
    }

    private static String categoryBudgetBody(long categoryId, String amount) {
        return """
                {"periodMonth":"2026-09","scopeType":"CATEGORY","categoryId":%d,"amount":"%s"}
                """.formatted(categoryId, amount);
    }

    private static List<Long> budgetIds(JsonNode rows) {
        List<Long> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("id").asLong()));
        return result;
    }
}
