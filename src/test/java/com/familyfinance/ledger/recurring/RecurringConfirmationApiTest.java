package com.familyfinance.ledger.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(RecurringConfirmationApiTest.FixedClockConfiguration.class)
@Transactional
class RecurringConfirmationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecurringService recurringService;
    @Autowired RecurringOccurrenceRepository occurrences;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired FinancialAccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired FamilyMemberRepository members;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void assignedMemberConfirmationIsIdempotentAndOnlyThenCountsTowardBudget() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession memberSession = join(owner, "recurring-confirm-member@example.com");
        AppUser memberUser = users.findByEmail("recurring-confirm-member@example.com").orElseThrow();
        Fixture fixture = fixture(memberUser);
        createCategoryBudget(owner, fixture.categoryId(), "1000.00");
        long ruleId = createRule(owner, fixture, "123.45");
        recurringService.generateDueOccurrences();
        RecurringOccurrence occurrence = occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId).get(0);

        mvc.perform(get("/api/budgets/usage").session(owner).param("periodMonth", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].spent").value("0.00"));
        MvcResult first = confirm(memberSession, occurrence.getId()).andExpect(status().isOk()).andReturn();
        MvcResult second = confirm(memberSession, occurrence.getId()).andExpect(status().isOk()).andReturn();
        long firstTransaction = data(first).path("confirmedTransactionId").asLong();
        long secondTransaction = data(second).path("confirmedTransactionId").asLong();

        assertThat(firstTransaction).isPositive().isEqualTo(secondTransaction);
        assertThat(transactions.countBySourceTypeAndSourceId(TransactionSourceType.RECURRING, occurrence.getId()))
                .isEqualTo(1);
        FinancialTransaction transaction = transactions.findById(firstTransaction).orElseThrow();
        assertThat(transaction.getCreatedByUser().getId()).isEqualTo(memberUser.getId());
        assertThat(transaction.getMember().getId()).isEqualTo(fixture.memberId());
        assertThat(transaction.getAccount().getId()).isEqualTo(fixture.accountId());
        assertThat(transaction.getCategory().getId()).isEqualTo(fixture.categoryId());
        assertThat(transaction.getKind()).isEqualTo(TransactionKind.EXPENSE);
        assertThat(transaction.getAmountCents()).isEqualTo(12_345L);
        assertThat(transaction.getOccurredOn()).isEqualTo(java.time.LocalDate.parse("2026-09-03"));
        assertThat(transaction.getSourceType()).isEqualTo(TransactionSourceType.RECURRING);
        assertThat(transaction.getSourceId()).isEqualTo(occurrence.getId());
        mvc.perform(get("/api/budgets/usage").session(owner).param("periodMonth", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].spent").value("123.45"));
    }

    @Test
    void onlyTheActiveAssignedUserCanConfirmAndUnassignedCancelledOrStaleOccurrencesAreRejected() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession assigned = join(owner, "recurring-assigned@example.com");
        MockHttpSession other = join(owner, "recurring-other@example.com");
        AppUser assignedUser = users.findByEmail("recurring-assigned@example.com").orElseThrow();
        Fixture fixture = fixture(assignedUser);

        long wrongUserRule = createRule(owner, fixture, "10.00");
        recurringService.generateDueOccurrences();
        long wrongUserOccurrence = occurrences.findByRuleIdOrderByDueOnAscIdAsc(wrongUserRule).get(0).getId();
        confirm(other, wrongUserOccurrence).andExpect(status().isForbidden());
        confirm(owner, wrongUserOccurrence).andExpect(status().isForbidden());

        jdbc.update("update recurring_occurrences set assigned_user_id=null where id=?", wrongUserOccurrence);
        confirm(assigned, wrongUserOccurrence)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OCCURRENCE_UNASSIGNED"));

        long cancelledRule = createRule(owner, fixture, "11.00");
        recurringService.generateDueOccurrences();
        long cancelledOccurrence = occurrences.findByRuleIdOrderByDueOnAscIdAsc(cancelledRule).get(0).getId();
        mvc.perform(delete("/api/recurring-rules/{id}", cancelledRule).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        confirm(assigned, cancelledOccurrence)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OCCURRENCE_CANCELLED"));

        long staleRule = createRule(owner, fixture, "12.00");
        recurringService.generateDueOccurrences();
        long staleOccurrence = occurrences.findByRuleIdOrderByDueOnAscIdAsc(staleRule).get(0).getId();
        jdbc.update("update financial_accounts set archived_at=current_timestamp where id=?", fixture.accountId());
        confirm(assigned, staleOccurrence)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_REFERENCE"));
        assertThat(transactions.countBySourceTypeAndSourceId(TransactionSourceType.RECURRING, staleOccurrence))
                .isZero();
    }

    @Test
    void occurrenceListIsStableBoundedAndFiltersStatusDateAndAssignee() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession memberSession = join(owner, "recurring-list-member@example.com");
        AppUser memberUser = users.findByEmail("recurring-list-member@example.com").orElseThrow();
        Fixture fixture = fixture(memberUser);
        long firstRule = createRule(owner, fixture, "20.00");
        long secondRule = createRule(owner, fixture, "30.00");
        recurringService.generateDueOccurrences();
        long firstOccurrence = occurrences.findByRuleIdOrderByDueOnAscIdAsc(firstRule).get(0).getId();
        confirm(memberSession, firstOccurrence).andExpect(status().isOk());

        mvc.perform(get("/api/recurring-occurrences").session(owner)
                        .param("status", "PENDING")
                        .param("from", "2026-09-03").param("to", "2026-09-03")
                        .param("assignedUserId", memberUser.getId().toString())
                        .param("page", "-2").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "1"))
                .andExpect(header().string("X-Total-Pages", "1"))
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data[0].ruleId").value(secondRule))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].assignedUserId").value(memberUser.getId()));

        memberUser.changeStatus(com.familyfinance.household.AppUserStatus.SUSPENDED);
        users.saveAndFlush(memberUser);
        mvc.perform(get("/api/recurring-occurrences").session(owner)
                        .param("assignedUserId", memberUser.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Elements", "2"));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(MockHttpSession session, long id)
            throws Exception {
        return mvc.perform(post("/api/recurring-occurrences/{id}/confirm", id).session(session).with(csrf()));
    }

    private long createRule(MockHttpSession owner, Fixture f, String amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/recurring-rules").session(owner).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"kind":"EXPENSE","amount":"%s","scheduleType":"MONTHLY","intervalValue":1,
                                 "dayOfMonth":3,"startOn":"2026-09-03","accountId":%d,"memberId":%d,
                                 "categoryId":%d,"assignedUserId":%d,"paused":false}
                                """.formatted(amount, f.accountId(), f.memberId(), f.categoryId(), f.assignedUserId())))
                .andExpect(status().isCreated()).andReturn();
        return data(result).path("id").asLong();
    }

    private void createCategoryBudget(MockHttpSession owner, long categoryId, String amount) throws Exception {
        mvc.perform(post("/api/budgets").session(owner).with(csrf()).contentType("application/json")
                        .content("""
                                {"periodMonth":"2026-09","scopeType":"CATEGORY","categoryId":%d,"amount":"%s"}
                                """.formatted(categoryId, amount)))
                .andExpect(status().isCreated());
    }

    private Fixture fixture(AppUser assignedUser) {
        long householdId = assignedUser.getHousehold().getId();
        FamilyMember member = members.findByHouseholdIdOrderById(householdId).stream()
                .filter(candidate -> candidate.getLinkedUser() != null)
                .filter(candidate -> candidate.getLinkedUser().getId().equals(assignedUser.getId()))
                .findFirst().orElseThrow();
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId)
                .orElseThrow().getId();
        long categoryId = categories.saveAndFlush(new Category(
                assignedUser.getHousehold(), TransactionKind.EXPENSE,
                "周期测试-" + Long.toUnsignedString(System.nanoTime(), 36), "#345678", false,
                Instant.parse("2026-09-02T16:30:00Z"))).getId();
        return new Fixture(accountId, member.getId(), categoryId, assignedUser.getId());
    }

    private MockHttpSession join(MockHttpSession owner, String email) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType("application/json").content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = data(invite).path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
                                {"email":"%s","displayName":"周期确认成员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    record Fixture(long accountId, long memberId, long categoryId, long assignedUserId) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary Clock recurringConfirmationClock() {
            return Clock.fixed(Instant.parse("2026-09-02T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
