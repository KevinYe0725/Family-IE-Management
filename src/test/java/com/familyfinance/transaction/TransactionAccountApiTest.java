package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import jakarta.persistence.EntityManager;
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
class TransactionAccountApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FinancialAccountRepository accounts;

    @Autowired
    FinancialTransactionRepository transactions;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    CategoryRepository categories;

    @Autowired
    AppUserRepository users;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createRequiresAnActiveAccountFromTheCurrentHousehold() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long householdId = users.findByEmail("demo@local.family").orElseThrow().getHousehold().getId();
        long memberId = members.findByHouseholdIdOrderById(householdId).get(0).getId();
        long categoryId = expenseCategoryId(householdId);

        mvc.perform(post("/api/transactions").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(null, memberId, categoryId, "缺少账户", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不能为空"));

        registerCreate("transaction-foreign@example.com", "流水外部家庭");
        long foreignHouseholdId = users.findByEmail("transaction-foreign@example.com")
                .orElseThrow().getHousehold().getId();
        long foreignAccountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(foreignHouseholdId)
                .orElseThrow().getId();
        String foreignBody = mvc.perform(post("/api/transactions").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(foreignAccountId, memberId, categoryId, "外部账户", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不存在"))
                .andReturn().getResponse().getContentAsString();
        String unknownBody = mvc.perform(post("/api/transactions").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(Long.MAX_VALUE, memberId, categoryId, "未知账户", null)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(foreignBody).isEqualTo(unknownBody).doesNotContain("流水外部家庭");

        long archivedAccountId = createAccount(owner, "已归档交易账户");
        mvc.perform(delete("/api/accounts/{id}", archivedAccountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/transactions").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(archivedAccountId, memberId, categoryId, "归档账户", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不存在"));
    }

    @Test
    void responseIncludesAccountAndListCanFilterByAccount() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long householdId = users.findByEmail("demo@local.family").orElseThrow().getHousehold().getId();
        long memberId = members.findByHouseholdIdOrderById(householdId).get(0).getId();
        long categoryId = expenseCategoryId(householdId);
        long firstAccountId = createAccount(owner, "筛选账户甲");
        long secondAccountId = createAccount(owner, "筛选账户乙");

        long firstTransactionId = createTransaction(
                owner, firstAccountId, memberId, categoryId, "账户筛选甲", null);
        createTransaction(owner, secondAccountId, memberId, categoryId, "账户筛选乙", null);

        mvc.perform(get("/api/transactions/{id}", firstTransactionId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(firstAccountId))
                .andExpect(jsonPath("$.data.accountName").value("筛选账户甲"));
        mvc.perform(get("/api/transactions").session(owner)
                        .param("accountId", Long.toString(firstAccountId))
                        .param("q", "账户筛选"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(firstTransactionId))
                .andExpect(jsonPath("$.data[0].accountId").value(firstAccountId));

        mvc.perform(get("/api/transactions").session(owner)
                        .param("accountId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不存在"));
    }

    @Test
    void creatorAlwaysUsesCurrentUserAndMembersOnlyMutateTheirOwnTransactions() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession firstMemberSession = join(owner, "transaction-member-a@example.com", HouseholdRole.MEMBER);
        MockHttpSession secondMemberSession = join(owner, "transaction-member-b@example.com", HouseholdRole.MEMBER);
        MockHttpSession adminSession = join(owner, "transaction-admin@example.com", HouseholdRole.ADMIN);
        var ownerUser = users.findByEmail("demo@local.family").orElseThrow();
        var firstMemberUser = users.findByEmail("transaction-member-a@example.com").orElseThrow();
        long householdId = ownerUser.getHousehold().getId();
        long ownerProfileId = linkedMember(householdId, ownerUser.getId()).getId();
        long categoryId = expenseCategoryId(householdId);
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId).orElseThrow().getId();

        long firstTransactionId = createTransaction(
                firstMemberSession, accountId, ownerProfileId, categoryId, "成员自己创建", ownerUser.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(transactions.findById(firstTransactionId).orElseThrow().getCreatedByUser().getId())
                .isEqualTo(firstMemberUser.getId());

        mvc.perform(patch("/api/transactions/{id}", firstTransactionId)
                        .session(secondMemberSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"越权修改\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/transactions/{id}", firstTransactionId)
                        .session(secondMemberSession).with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/transactions/{id}", firstTransactionId)
                        .session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"所有者可修改\",\"createdByUserId\":" + ownerUser.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("所有者可修改"));
        mvc.perform(patch("/api/transactions/{id}", firstTransactionId)
                        .session(adminSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"管理员也可修改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").value("管理员也可修改"));
        entityManager.flush();
        entityManager.clear();
        assertThat(transactions.findById(firstTransactionId).orElseThrow().getCreatedByUser().getId())
                .isEqualTo(firstMemberUser.getId());

        long ownDeleteId = createTransaction(
                firstMemberSession, accountId, ownerProfileId, categoryId, "成员自己删除", null);
        mvc.perform(delete("/api/transactions/{id}", ownDeleteId)
                        .session(firstMemberSession).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void changingTransactionAccountDoesNotChangeItsCreator() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession memberSession = join(owner, "transaction-account-owner@example.com", HouseholdRole.MEMBER);
        var memberUser = users.findByEmail("transaction-account-owner@example.com").orElseThrow();
        long householdId = memberUser.getHousehold().getId();
        long memberId = linkedMember(householdId, memberUser.getId()).getId();
        long categoryId = expenseCategoryId(householdId);
        long firstAccountId = createAccount(owner, "原交易账户");
        long secondAccountId = createAccount(owner, "新交易账户");
        long transactionId = createTransaction(
                memberSession, firstAccountId, memberId, categoryId, "切换账户", null);

        mvc.perform(patch("/api/transactions/{id}", transactionId)
                        .session(memberSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + secondAccountId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(secondAccountId))
                .andExpect(jsonPath("$.data.accountName").value("新交易账户"));

        entityManager.flush();
        entityManager.clear();
        FinancialTransaction saved = transactions.findById(transactionId).orElseThrow();
        assertThat(saved.getAccount().getId()).isEqualTo(secondAccountId);
        assertThat(saved.getCreatedByUser().getId()).isEqualTo(memberUser.getId());
    }

    @Test
    void loanSourcedHistoryCannotBeDeletedByItsMemberCreatorOrAnOwner() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession memberSession = join(owner, "loan-source-owner@example.com", HouseholdRole.MEMBER);
        var memberUser = users.findByEmail("loan-source-owner@example.com").orElseThrow();
        long householdId = memberUser.getHousehold().getId();
        long memberId = linkedMember(householdId, memberUser.getId()).getId();
        long categoryId = expenseCategoryId(householdId);
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId)
                .orElseThrow().getId();
        long transactionId = createTransaction(
                memberSession, accountId, memberId, categoryId, "贷款来源历史", null);
        jdbc.update("update financial_transactions set source_type='LOAN', source_id=? where id=?",
                900_000L + transactionId, transactionId);
        entityManager.clear();

        mvc.perform(delete("/api/transactions/{id}", transactionId)
                        .session(memberSession).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("历史")));
        mvc.perform(delete("/api/transactions/{id}", transactionId)
                        .session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
        assertThat(transactions.findById(transactionId)).isPresent();
    }

    private long createTransaction(
            MockHttpSession session,
            long accountId,
            long memberId,
            long categoryId,
            String note,
            Long attemptedCreatorId) throws Exception {
        MvcResult result = mvc.perform(post("/api/transactions").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionBody(accountId, memberId, categoryId, note, attemptedCreatorId)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private long createAccount(MockHttpSession owner, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/accounts").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"BANK","currency":"CNY","openingBalance":"0.00"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role.name() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = body(invite).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"交易成员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private void registerCreate(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"外部交易用户","password":"family-pass-2026",
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

    private FamilyMember linkedMember(long householdId, long userId) {
        return members.findByHouseholdIdOrderById(householdId).stream()
                .filter(member -> member.getLinkedUser() != null)
                .filter(member -> member.getLinkedUser().getId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    private long expenseCategoryId(long householdId) {
        return categories.findByHouseholdIdOrderById(householdId).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String transactionBody(
            Long accountId,
            long memberId,
            long categoryId,
            String note,
            Long attemptedCreatorId) {
        String account = accountId == null ? "" : "\"accountId\":" + accountId + ",";
        String creator = attemptedCreatorId == null ? "" : "\"createdByUserId\":" + attemptedCreatorId + ",";
        return """
                {%s%s"kind":"expense","amount":"12.30","occurredOn":"2026-09-18",
                 "memberId":%d,"categoryId":%d,"note":"%s"}
                """.formatted(account, creator, memberId, categoryId, note);
    }
}
