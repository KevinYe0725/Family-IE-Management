package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
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
class TransactionSummaryApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HouseholdRepository households;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired FinancialAccountRepository accounts;
    @Autowired FinancialTransactionRepository transactions;

    @Test
    void summaryAggregatesEveryFilteredTransactionBeyondTheUiPage() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category food = category(household, TransactionKind.EXPENSE, "餐饮");
        FinancialAccount account = account(household, "summary-cny", "CNY");
        AppUser creator = demoUser();

        for (int index = 0; index < 55; index++) {
            transactions.save(new FinancialTransaction(
                    household,
                    account,
                    creator,
                    member,
                    food,
                    TransactionKind.EXPENSE,
                    100L + index,
                    LocalDate.of(2026, 9, 1).plusDays(index % 3),
                    null,
                    null,
                    "summary-over-page-" + index,
                    TEST_TIME,
                    TEST_TIME));
        }
        transactions.flush();

        MvcResult result = mvc.perform(get("/api/transactions/summary")
                        .session(session)
                        .param("month", "2026-09")
                        .param("kind", "expense")
                        .param("q", "summary-over-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.income").value("0.00"))
                .andExpect(jsonPath("$.data.expense").value("69.85"))
                .andExpect(jsonPath("$.data.balance").value("-69.85"))
                .andExpect(jsonPath("$.data.transactionCount").value(55))
                .andExpect(jsonPath("$.data.unconvertedCount").value(0))
                .andReturn();

        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode category = find(data.path("categories"), "categoryId", food.getId().toString());
        assertThat(category.path("name").asText()).isEqualTo("餐饮");
        assertThat(category.path("color").asText()).isEqualTo(food.getColor());
        assertThat(category.path("kind").asText()).isEqualTo("expense");
        assertThat(category.path("amount").asText()).isEqualTo("69.85");
        assertThat(category.path("count").asInt()).isEqualTo(55);

        JsonNode firstDay = find(data.path("daily"), "date", "2026-09-01");
        assertThat(firstDay.path("kind").asText()).isEqualTo("expense");
        assertThat(firstDay.path("categoryId").asLong()).isEqualTo(food.getId());
        assertThat(firstDay.path("amount").asText()).isEqualTo("24.13");
        assertThat(firstDay.path("count").asInt()).isEqualTo(19);
    }

    @Test
    void summaryUsesDateAndKindFiltersAndNeverLeaksAnotherHousehold() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category expense = category(household, TransactionKind.EXPENSE, "筛选支出");
        Category income = category(household, TransactionKind.INCOME, "筛选收入");
        FinancialAccount account = account(household, "summary-filter-cny", "CNY");
        AppUser creator = demoUser();
        String marker = "summary-filter";

        transactions.save(new FinancialTransaction(household, account, creator, member, expense,
                TransactionKind.EXPENSE, 1250L, LocalDate.of(2026, 9, 12), null, null, marker, TEST_TIME, TEST_TIME));
        transactions.save(new FinancialTransaction(household, account, creator, member, income,
                TransactionKind.INCOME, 9900L, LocalDate.of(2026, 9, 12), null, null, marker, TEST_TIME, TEST_TIME));
        transactions.save(new FinancialTransaction(household, account, creator, member, expense,
                TransactionKind.EXPENSE, 7700L, LocalDate.of(2026, 10, 12), null, null, marker, TEST_TIME, TEST_TIME));

        Household outsider = households.save(new Household("summary outsider", TEST_TIME));
        AppUser outsiderUser = users.save(new AppUser(outsider, "summary-outsider", "summary-outsider@example.com",
                "外部用户", "encoded-password", TEST_TIME));
        Category outsiderCategory = categories.save(new Category(outsider, TransactionKind.EXPENSE,
                "外部支出", "#123456", false, TEST_TIME));
        FinancialAccount outsiderAccount = accounts.save(new FinancialAccount(
                outsider, "外部账户", AccountType.CASH, "CNY", 0L));
        transactions.save(new FinancialTransaction(outsider, outsiderAccount, outsiderUser, null, outsiderCategory,
                TransactionKind.EXPENSE, 8800L, LocalDate.of(2026, 9, 12), null, null, marker, TEST_TIME, TEST_TIME));
        transactions.flush();

        mvc.perform(get("/api/transactions/summary")
                        .session(session)
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30")
                        .param("kind", "expense")
                        .param("q", marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionCount").value(1))
                .andExpect(jsonPath("$.data.income").value("0.00"))
                .andExpect(jsonPath("$.data.expense").value("12.50"))
                .andExpect(jsonPath("$.data.balance").value("-12.50"));
    }

    @Test
    void sharedMemberTransactionRemainsSearchableWhenKeywordMatchesAnotherField() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        Category food = category(household, TransactionKind.EXPENSE, "家庭共同支出");
        FinancialAccount account = account(household, "summary-shared-cny", "CNY");
        FinancialTransaction shared = transactions.save(new FinancialTransaction(
                household,
                account,
                demoUser(),
                null,
                food,
                TransactionKind.EXPENSE,
                500L,
                LocalDate.of(2026, 9, 14),
                null,
                null,
                "shared-member-keyword",
                TEST_TIME,
                TEST_TIME));
        transactions.flush();

        mvc.perform(get("/api/transactions/summary")
                        .session(session)
                        .param("q", "shared-member-keyword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionCount").value(1))
                .andExpect(jsonPath("$.data.expense").value("5.00"))
                .andExpect(jsonPath("$.data.daily[0].categoryId").value(food.getId()))
                .andExpect(jsonPath("$.data.categories[0].categoryId").value(food.getId()));

        mvc.perform(get("/api/transactions")
                        .session(session)
                        .param("q", "shared-member-keyword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(shared.getId()));
    }

    @Test
    void missingForeignCurrencyReferencePropagatesNullInsteadOfZero() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        Category food = category(household, TransactionKind.EXPENSE, "外币支出");
        FinancialAccount usd = account(household, "summary-usd-missing", "USD");
        transactions.save(new FinancialTransaction(
                household,
                usd,
                demoUser(),
                null,
                food,
                TransactionKind.EXPENSE,
                1234L,
                LocalDate.of(2026, 9, 15),
                null,
                null,
                "summary-fx-missing",
                TEST_TIME,
                TEST_TIME));
        transactions.flush();

        MvcResult result = mvc.perform(get("/api/transactions/summary")
                        .session(session)
                        .param("q", "summary-fx-missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.income").value("0.00"))
                .andExpect(jsonPath("$.data.expense").isEmpty())
                .andExpect(jsonPath("$.data.balance").isEmpty())
                .andExpect(jsonPath("$.data.transactionCount").value(1))
                .andExpect(jsonPath("$.data.unconvertedCount").value(1))
                .andReturn();

        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(find(data.path("categories"), "name", "外币支出").path("amount").isNull()).isTrue();
        assertThat(find(data.path("daily"), "date", "2026-09-15").path("amount").isNull()).isTrue();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Household demoHousehold() {
        return users.findByEmail("demo@local.family").orElseThrow().getHousehold();
    }

    private AppUser demoUser() {
        return users.findByEmail("demo@local.family").orElseThrow();
    }

    private Category category(Household household, TransactionKind kind, String name) {
        return categories.findByHouseholdOrderById(household).stream()
                .filter(item -> item.getKind() == kind)
                .filter(item -> item.getName().equals(name))
                .findFirst()
                .orElseGet(() -> categories.save(new Category(household, kind, name, "#445566", false, TEST_TIME)));
    }

    private FinancialAccount account(Household household, String name, String currency) {
        return accounts.save(new FinancialAccount(household, name, AccountType.CASH, currency, 0L));
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("No row with " + field + "=" + value + ": " + array);
    }
}
