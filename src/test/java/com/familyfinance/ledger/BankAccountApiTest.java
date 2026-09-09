package com.familyfinance.ledger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.FamilyMemberRepository;
import java.util.UUID;
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
@SpringBootTest(properties = {"app.seed.enabled=true", "app.multicurrency.enabled=true"})
@AutoConfigureMockMvc
@Transactional
class BankAccountApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;

    @Test
    void createTwoCurrenciesSharesParentAndReplayDoesNotPostAnotherOpening() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        String key = "bank-create-" + UUID.randomUUID();
        String body = """
                {"name":"汇丰 One","bankName":"汇丰银行","cardLastFour":"1234","balances":[
                  {"currency":"CNY","openingBalance":"100.00","openingOn":"2026-01-01"},
                  {"currency":"USD","openingBalance":"20.00","openingOn":"2026-01-01"}]}
                """;

        MvcResult first = mvc.perform(post("/api/bank-accounts").session(owner).with(csrf())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("汇丰 One"))
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andReturn();
        JsonNode firstData = mapper.readTree(first.getResponse().getContentAsString()).path("data");
        long parentId = firstData.path("id").asLong();
        long firstChildId = firstData.path("accounts").get(0).path("id").asLong();
        long secondChildId = firstData.path("accounts").get(1).path("id").asLong();
        org.assertj.core.api.Assertions.assertThat(firstChildId).isNotEqualTo(secondChildId);
        org.assertj.core.api.Assertions.assertThat(firstData.path("accounts").get(0).path("bankAccountId").asLong())
                .isEqualTo(parentId);
        org.assertj.core.api.Assertions.assertThat(firstData.path("accounts").get(1).path("bankAccountId").asLong())
                .isEqualTo(parentId);

        mvc.perform(post("/api/bank-accounts").session(owner).with(csrf())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(parentId))
                .andExpect(jsonPath("$.data.accounts.length()").value(2));

        long openings = jdbc.queryForObject(
                "select count(distinct j.id) from ledger_journals j join ledger_entries e on e.household_id=j.household_id and e.journal_id=j.id where j.household_id=(select household_id from bank_accounts where id=?) and j.source_type='CASH_OPENING' and e.account_code in (select concat('CASH:', id) from financial_accounts where bank_account_id=?)",
                Long.class, parentId, parentId);
        org.assertj.core.api.Assertions.assertThat(openings).isEqualTo(2);
    }

    @Test
    void duplicateCurrencyIsRejected() throws Exception {
        mvc.perform(post("/api/bank-accounts").session(login("demo", "demo1234")).with(csrf())
                        .header("Idempotency-Key", "bank-duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"重复币种卡","bankName":"银行","balances":[
                                  {"currency":"CNY","openingBalance":"0.00","openingOn":"2026-01-01"},
                                  {"currency":"CNY","openingBalance":"0.00","openingOn":"2026-01-01"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.balances").exists());
    }

    @Test
    void foreignHouseholdCannotReadOrEditParent() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession foreign = registerAndLogin("bank-foreign-" + UUID.randomUUID() + "@example.com", "外部银行卡家庭");
        MvcResult created = mvc.perform(post("/api/bank-accounts").session(foreign).with(csrf())
                        .header("Idempotency-Key", "foreign-bank-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"外部主卡","bankName":"外部银行","balances":[
                                  {"currency":"CNY","openingBalance":"0.00","openingOn":"2026-01-01"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mvc.perform(get("/api/bank-accounts").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)]".formatted(id)).doesNotExist());
        mvc.perform(patch("/api/bank-accounts/{id}", id).session(owner).with(csrf())
                        .header("Idempotency-Key", "foreign-edit-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/bank-accounts/{id}/balances", id).session(owner).with(csrf())
                        .header("Idempotency-Key", "foreign-balance-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentArchiveRejectsNonZeroChildBalanceAndActiveBinding() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MvcResult created = mvc.perform(post("/api/bank-accounts").session(owner).with(csrf())
                        .header("Idempotency-Key", "bank-archive-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"归档主卡","bankName":"银行","balances":[
                                  {"currency":"CNY","openingBalance":"10.00","openingOn":"2026-01-01"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        JsonNode data = mapper.readTree(created.getResponse().getContentAsString()).path("data");
        long parentId = data.path("id").asLong();
        long accountId = data.path("accounts").get(0).path("id").asLong();

        mvc.perform(delete("/api/bank-accounts/{id}", parentId).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_BALANCE_NOT_ZERO"));

        mvc.perform(patch("/api/accounts/{id}", accountId).session(owner).with(csrf())
                        .header("Idempotency-Key", "zero-opening-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isOk());
        long householdId = jdbc.queryForObject("select household_id from financial_accounts where id=?", Long.class, accountId);
        long memberId = members.findByHouseholdIdOrderById(householdId).get(0).getId();
        long categoryId = categories.findByHouseholdIdOrderById(householdId).stream()
                .filter(c -> c.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow().getId();
        long actorId = memberships.findByHouseholdIdOrderById(householdId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE && m.getRole() == HouseholdRole.OWNER)
                .findFirst().orElseThrow().getUser().getId();
        jdbc.update("""
                insert into recurring_rules
                    (household_id,kind,amount_cents,schedule_type,interval_value,day_of_month,next_due_on,
                     account_id,member_id,category_id,active,created_by)
                values (?, 'EXPENSE', 100, 'MONTHLY', 1, 15, date '2026-09-15', ?, ?, ?, true, ?)
                """, householdId, accountId, memberId, categoryId, actorId);
        mvc.perform(delete("/api/bank-accounts/{id}", parentId).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void parentArchiveArchivesEveryCurrencyChildAndRemovesParentFromActiveList() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MvcResult created = mvc.perform(post("/api/bank-accounts").session(owner).with(csrf())
                        .header("Idempotency-Key", "bank-archive-empty-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"空余额主卡","bankName":"银行","balances":[
                                  {"currency":"CNY","openingBalance":"0.00","openingOn":"2026-01-01"},
                                  {"currency":"USD","openingBalance":"0.00","openingOn":"2026-01-01"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        JsonNode data = mapper.readTree(created.getResponse().getContentAsString()).path("data");
        long parentId = data.path("id").asLong();
        long firstChild = data.path("accounts").get(0).path("id").asLong();
        long secondChild = data.path("accounts").get(1).path("id").asLong();

        mvc.perform(delete("/api/bank-accounts/{id}", parentId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/{id}", firstChild).session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.archivedAt").exists());
        mvc.perform(get("/api/accounts/{id}", secondChild).session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.archivedAt").exists());
        mvc.perform(get("/api/bank-accounts").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(parentId)).doesNotExist());
    }

    @Test
    void parentRenameRejectsDerivedChildNameConflictWithoutChangingState() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MvcResult created = mvc.perform(post("/api/bank-accounts").session(owner).with(csrf())
                        .header("Idempotency-Key", "bank-rename-conflict-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"原始主卡","bankName":"银行","balances":[
                                  {"currency":"CNY","openingBalance":"0.00","openingOn":"2026-01-01"},
                                  {"currency":"USD","openingBalance":"0.00","openingOn":"2026-01-01"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        JsonNode data = mapper.readTree(created.getResponse().getContentAsString()).path("data");
        long parentId = data.path("id").asLong();
        mvc.perform(post("/api/accounts").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"目标名 · CNY\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/bank-accounts/{id}", parentId).session(owner).with(csrf())
                        .header("Idempotency-Key", "bank-rename-conflict-patch")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"目标名\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));
        mvc.perform(get("/api/bank-accounts").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d)].name".formatted(parentId)).value("原始主卡"))
                .andExpect(jsonPath("$.data[?(@.id == %d)].accounts[0].name".formatted(parentId)).value("原始主卡 · CNY"));
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
