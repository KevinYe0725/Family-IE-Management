package com.familyfinance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class AccountApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FinancialAccountRepository accounts;

    @Autowired
    FinancialTransactionRepository transactions;

    @Autowired
    HouseholdRepository households;

    @Autowired
    HouseholdMembershipRepository memberships;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    CategoryRepository categories;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Test
    void ownerAndAdminManageAllAccountTypesWhileMemberCanOnlyRead() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, "account-admin@example.com", HouseholdRole.ADMIN);
        MockHttpSession member = join(owner, "account-member@example.com", HouseholdRole.MEMBER);

        long cashId = createAccount(owner, "家庭现金", "CASH", "CNY", "0.00");
        long bankId = createAccount(admin, "家庭银行卡", "BANK", "CNY", "1000.00");
        long walletId = createAccount(owner, "家庭钱包", "WALLET", "CNY", "88.08");

        mvc.perform(get("/api/accounts/{id}", bankId).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("家庭银行卡"))
                .andExpect(jsonPath("$.data.type").value("BANK"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.openingBalance").value("1000.00"));
        mvc.perform(get("/api/accounts").session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == %d)]".formatted(cashId)).exists())
                .andExpect(jsonPath("$.data.items[?(@.id == %d)]".formatted(walletId)).exists());

        mvc.perform(post("/api/accounts").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody("成员账户", "CASH", "CNY", "1.00")))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/accounts/{id}", bankId).session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"成员改名\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/accounts/{id}", bankId).session(member).with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/accounts/{id}", bankId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"工资卡\",\"openingBalance\":\"1200.50\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("工资卡"))
                .andExpect(jsonPath("$.data.openingBalance").value("1200.50"));
    }

    @Test
    void joiningAnExistingHouseholdDoesNotCreateAnotherDefaultAccount() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long householdId = households.findAll().get(0).getId();
        long before = accounts.count();

        join(owner, "account-join@example.com", HouseholdRole.MEMBER);

        assertThat(accounts.count()).isEqualTo(before);
        assertThat(accounts.findAll().stream()
                .filter(account -> account.getHousehold().getId().equals(householdId))
                .filter(account -> FinancialAccount.DEFAULT_NAME.equals(account.getName())))
                .hasSize(1);
    }

    @Test
    void normalizedNamesAreUniquePerHouseholdAndCurrencyIsFixedToCny() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        createAccount(owner, "  工资卡  ", "BANK", "CNY", "10.00");

        mvc.perform(post("/api/accounts").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody("工资卡", "BANK", "CNY", "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));
        mvc.perform(post("/api/accounts").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody("美元账户", "BANK", "USD", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.currency").exists());
        mvc.perform(post("/api/accounts").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody("错误金额", "BANK", "CNY", "1.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.openingBalance").exists());

        registerCreate("foreign-account-owner@example.com", "外部家庭");
        MockHttpSession foreignOwner = login("foreign-account-owner@example.com", "family-pass-2026");
        createAccount(foreignOwner, "工资卡", "BANK", "CNY", "20.00");
    }

    @Test
    void openingBalanceAcceptsExactSignedCents() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");

        long accountId = createAccount(owner, "透支账户", "BANK", "CNY", "-0.50");

        mvc.perform(get("/api/accounts/{id}", accountId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.openingBalance").value("-0.50"));
        assertThat(accounts.findById(accountId).orElseThrow().getOpeningBalanceCents()).isEqualTo(-50L);
    }

    @Test
    void accountListClampsPageBoundsAndUsesStableDescendingIds() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        for (int index = 0; index < 52; index++) {
            createAccount(owner, "分页账户-" + index, "CASH", "CNY", "0.00");
        }

        JsonNode page = body(mvc.perform(get("/api/accounts")
                        .session(owner)
                        .param("page", "-7")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "53"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andReturn()).path("data").path("items");

        List<Long> ids = new ArrayList<>();
        page.forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void accountLookupAndMutationDoNotLeakAnotherHousehold() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        registerCreate("foreign-isolation@example.com", "隔离家庭");
        MockHttpSession foreignOwner = login("foreign-isolation@example.com", "family-pass-2026");
        long foreignId = createAccount(foreignOwner, "外部银行卡", "BANK", "CNY", "1.00");

        String foreignGet = mvc.perform(get("/api/accounts/{id}", foreignId).session(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String unknownGet = mvc.perform(get("/api/accounts/{id}", Long.MAX_VALUE).session(owner))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(foreignGet).isEqualTo(unknownGet).doesNotContain("外部银行卡").doesNotContain("隔离家庭");

        mvc.perform(patch("/api/accounts/{id}", foreignId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权修改\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/accounts/{id}", foreignId).session(owner).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void activeRecurringReferenceBlocksArchiveUntilRuleIsInactive() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long accountId = createAccount(owner, "周期账单卡", "BANK", "CNY", "0.00");
        long householdId = households.findAll().get(0).getId();
        long memberId = members.findByHouseholdIdOrderById(householdId).get(0).getId();
        long categoryId = categories.findByHouseholdIdOrderById(householdId).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE)
                .findFirst().orElseThrow().getId();
        long creatorId = memberships.findByHouseholdIdOrderById(householdId).stream()
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .filter(membership -> membership.getRole() == HouseholdRole.OWNER)
                .findFirst().orElseThrow().getUser().getId();
        jdbc.update("""
                insert into recurring_rules
                    (household_id, kind, amount_cents, schedule_type, interval_value, day_of_month,
                     next_due_on, account_id, member_id, category_id, active, created_by)
                values (?, 'EXPENSE', 500, 'MONTHLY', 1, 15, date '2026-09-15', ?, ?, ?, true, ?)
                """, householdId, accountId, memberId, categoryId, creatorId);

        mvc.perform(delete("/api/accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));

        jdbc.update("update recurring_rules set active=false where account_id=?", accountId);
        mvc.perform(delete("/api/accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void archivePreservesHistoricalTransactionsAndRepeatedDeleteIsIdempotent() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long accountId = createAccount(owner, "历史账户", "CASH", "CNY", "50.00");
        FinancialAccount account = accounts.findById(accountId).orElseThrow();
        var household = account.getHousehold();
        var ownerMembership = memberships.findByHouseholdIdOrderById(household.getId()).stream()
                .filter(membership -> membership.getRole() == HouseholdRole.OWNER)
                .findFirst().orElseThrow();
        var member = members.findByHouseholdIdOrderById(household.getId()).get(0);
        var category = categories.findByHouseholdIdOrderById(household.getId()).stream()
                .filter(candidate -> candidate.getKind() == TransactionKind.EXPENSE)
                .findFirst().orElseThrow();
        FinancialTransaction transaction = transactions.saveAndFlush(new FinancialTransaction(
                household,
                account,
                ownerMembership.getUser(),
                member,
                category,
                TransactionKind.EXPENSE,
                1234L,
                LocalDate.parse("2026-09-20"),
                "历史商家",
                null,
                null,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z")));

        mvc.perform(delete("/api/accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        accounts.flush();
        entityManager.clear();
        Instant firstArchivedAt = accounts.findById(accountId).orElseThrow().getArchivedAt();
        assertThat(firstArchivedAt).isNotNull();
        assertThat(transactions.findById(transaction.getId())).isPresent();

        mvc.perform(delete("/api/accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        accounts.flush();
        entityManager.clear();
        Instant secondArchivedAt = accounts.findById(accountId).orElseThrow().getArchivedAt();
        assertThat(secondArchivedAt).isEqualTo(firstArchivedAt);
        mvc.perform(get("/api/accounts/{id}", accountId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archivedAt").exists());
        mvc.perform(get("/api/accounts").session(owner))
                .andExpect(jsonPath("$.data.items[?(@.id == %d)]".formatted(accountId)).doesNotExist());
    }

    private long createAccount(
            MockHttpSession session, String name, String type, String currency, String openingBalance) throws Exception {
        MvcResult result = mvc.perform(post("/api/accounts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody(name, type, currency, openingBalance)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult createdInvite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = body(createdInvite).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"账户协作者","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private void registerCreate(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"外部所有者","password":"family-pass-2026",
                                 "mode":"CREATE","householdName":"%s"}
                                """.formatted(email, householdName)))
                .andExpect(status().isCreated());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String accountBody(String name, String type, String currency, String openingBalance) {
        return """
                {"name":"%s","type":"%s","currency":"%s","openingBalance":"%s"}
                """.formatted(name, type, currency, openingBalance);
    }
}
