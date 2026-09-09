package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.accounting.AccountingTestFixtures;
import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.household.AppUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = {"app.seed.enabled=true", "app.multicurrency.enabled=true"})
@AutoConfigureMockMvc
@Transactional
class BankAccountTransactionFilterApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired HouseholdRepository households;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired FinancialAccountRepository accounts;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired HouseholdMembershipRepository memberships;

    @Test
    void bankAccountFilterIncludesBothChildrenAndCsvUsesSameParentScope() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        JsonNode first = createParent(owner, "筛选主卡");
        JsonNode second = createParent(owner, "筛选其他卡");
        long firstParent = first.path("id").asLong();
        long secondParent = second.path("id").asLong();
        long cny = first.path("accounts").get(0).path("id").asLong();
        long usd = first.path("accounts").get(1).path("id").asLong();
        long other = second.path("accounts").get(0).path("id").asLong();
        createTransactions(cny, usd, other);

        mvc.perform(get("/api/transactions").session(owner)
                        .param("bankAccountId", Long.toString(firstParent)).param("q", "bank-filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].note").value(org.hamcrest.Matchers.containsInAnyOrder("bank-filter-cny", "bank-filter-usd")));
        mvc.perform(get("/api/transactions").session(owner)
                        .param("bankAccountId", Long.toString(secondParent)).param("q", "bank-filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].note").value("bank-filter-other"));

        MvcResult export = mvc.perform(get("/api/export.csv").session(owner)
                        .param("bankAccountId", Long.toString(firstParent)).param("q", "bank-filter"))
                .andExpect(status().isOk()).andReturn();
        String csv = new String(export.getResponse().getContentAsByteArray(), 3,
                export.getResponse().getContentAsByteArray().length - 3, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("bank-filter-cny", "bank-filter-usd").doesNotContain("bank-filter-other");
    }

    @Test
    void foreignHouseholdBankAccountSelectorIsRejected() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession foreign = registerAndLogin("bank-filter-foreign-" + UUID.randomUUID() + "@example.com", "筛选外部家庭");
        JsonNode foreignParent = createParent(foreign, "外部筛选卡");

        mvc.perform(get("/api/transactions").session(owner)
                        .param("bankAccountId", Long.toString(foreignParent.path("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.bankAccountId").exists());
        mvc.perform(get("/api/export.csv").session(owner)
                        .param("bankAccountId", Long.toString(foreignParent.path("id").asLong())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.bankAccountId").exists());
    }

    private JsonNode createParent(MockHttpSession session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/bank-accounts").session(session).with(csrf())
                        .header("Idempotency-Key", "filter-parent-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","bankName":"筛选银行","balances":[
                                  {"currency":"CNY","openingBalance":"10.00","openingOn":"2026-01-01"},
                                  {"currency":"USD","openingBalance":"10.00","openingOn":"2026-01-01"}]}
                                """.formatted(name)))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void createTransactions(long cny, long usd, long other) {
        long householdId = households.findAll().get(0).getId();
        var household = households.findById(householdId).orElseThrow();
        var creator = users.findByEmail("demo@local.family").orElseThrow();
        var member = members.findByHouseholdOrderById(household).get(0);
        Category category = categories.findByHouseholdOrderById(household).stream()
                .filter(item -> item.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow();
        transactions.save(new FinancialTransaction(household, accounts.findById(cny).orElseThrow(), creator, member,
                category, TransactionKind.EXPENSE, 100L, LocalDate.of(2026, 9, 3), null, null,
                "bank-filter-cny", TEST_TIME, TEST_TIME));
        transactions.save(new FinancialTransaction(household, accounts.findById(usd).orElseThrow(), creator, member,
                category, TransactionKind.EXPENSE, 200L, LocalDate.of(2026, 9, 4), null, null,
                "bank-filter-usd", TEST_TIME, TEST_TIME));
        transactions.save(new FinancialTransaction(household, accounts.findById(other).orElseThrow(), creator, member,
                category, TransactionKind.EXPENSE, 300L, LocalDate.of(2026, 9, 5), null, null,
                "bank-filter-other", TEST_TIME, TEST_TIME));
        transactions.flush();
        AccountingTestFixtures.postFixtureTransactions(context, householdId);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession registerAndLogin(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"外部所有者","password":"family-pass-2026",
                                 "mode":"CREATE","householdName":"%s"}
                                """.formatted(email, householdName)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }
}
