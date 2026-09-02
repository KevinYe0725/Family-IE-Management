package com.familyfinance.ledger.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(RecurringGenerationTest.FixedClockConfiguration.class)
@Transactional
class RecurringGenerationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecurringService recurringService;
    @Autowired RecurringRuleRepository rules;
    @Autowired RecurringOccurrenceRepository occurrences;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired FinancialAccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired FamilyMemberRepository members;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void monthlyDayThirtyOneClampsAtMonthEndAndRerunCreatesNoDuplicatesOrTransactions() throws Exception {
        MockHttpSession owner = login();
        Fixture fixture = fixture();
        long transactionCount = transactions.count();
        long ruleId = createRule(owner, monthlyBody(fixture, 31, "2026-01-31", null, false))
                .path("id").asLong();

        recurringService.generateDueOccurrences();
        recurringService.generateDueOccurrences();

        assertThat(occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId))
                .extracting(RecurringOccurrence::getDueOn)
                .containsExactly(
                        java.time.LocalDate.parse("2026-01-31"),
                        java.time.LocalDate.parse("2026-02-28"),
                        java.time.LocalDate.parse("2026-03-31"));
        assertThat(rules.findById(ruleId).orElseThrow().getNextDueOn())
                .isEqualTo(java.time.LocalDate.parse("2026-04-30"));
        assertThat(transactions.count()).isEqualTo(transactionCount);
    }

    @Test
    void weeklyIntervalUsesRequestedWeekdayAndAdvancesFromTheLastDueDate() throws Exception {
        MockHttpSession owner = login();
        Fixture fixture = fixture();
        long ruleId = createRule(owner, weeklyBody(fixture, 2, "MONDAY", "2026-01-06", null, false))
                .path("id").asLong();

        recurringService.generateDueOccurrences();

        assertThat(occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId))
                .extracting(RecurringOccurrence::getDueOn)
                .containsExactly(
                        java.time.LocalDate.parse("2026-01-12"),
                        java.time.LocalDate.parse("2026-01-26"),
                        java.time.LocalDate.parse("2026-02-09"),
                        java.time.LocalDate.parse("2026-02-23"),
                        java.time.LocalDate.parse("2026-03-09"),
                        java.time.LocalDate.parse("2026-03-23"));
        assertThat(rules.findById(ruleId).orElseThrow().getNextDueOn())
                .isEqualTo(java.time.LocalDate.parse("2026-04-06"));
    }

    @Test
    void pausedAndEndedRulesGenerateNothingAndCrudIsAdminOnlyBoundedAndArchived() throws Exception {
        MockHttpSession owner = login();
        Fixture fixture = fixture();
        long pausedId = createRule(owner, monthlyBody(fixture, 3, "2026-02-03", null, true))
                .path("id").asLong();
        JsonNode ended = createRule(owner, monthlyBody(fixture, 3, "2026-02-01", "2026-02-27", false));
        long endedId = ended.path("id").asLong();
        assertThat(ended.path("nextDueOn").asText()).isEqualTo("2026-02-03");

        recurringService.generateDueOccurrences();
        assertThat(occurrences.findByRuleIdOrderByDueOnAscIdAsc(pausedId)).isEmpty();
        assertThat(occurrences.findByRuleIdOrderByDueOnAscIdAsc(endedId)).isEmpty();
        assertThat(rules.findById(endedId).orElseThrow().getNextDueOn()).isNull();

        mvc.perform(get("/api/recurring-rules").session(owner)
                        .param("page", "-1").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "2"))
                .andExpect(header().string("X-Total-Pages", "1"))
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data[0].id").value(endedId))
                .andExpect(jsonPath("$.data[1].id").value(pausedId));

        mvc.perform(patch("/api/recurring-rules/{id}", pausedId).session(owner).with(csrf())
                        .contentType("application/json")
                        .content("{\"paused\":false,\"amount\":\"150.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paused").value(false))
                .andExpect(jsonPath("$.data.amount").value("150.00"));
        mvc.perform(delete("/api/recurring-rules/{id}", pausedId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(rules.findById(pausedId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void createRejectsForeignOrInactiveReferencesAndMembersCannotManageRules() throws Exception {
        MockHttpSession owner = login();
        MockHttpSession memberSession = join(owner, "recurring-member@example.com");
        Fixture fixture = fixture();
        AppUser memberUser = users.findByEmail("recurring-member@example.com").orElseThrow();
        FamilyMember memberProfile = linkedMember(fixture.householdId(), memberUser.getId());
        String valid = monthlyBody(new Fixture(
                fixture.householdId(), fixture.accountId(), fixture.categoryId(), memberProfile.getId(), memberUser.getId()),
                3, "2026-03-03", null, false);

        mvc.perform(post("/api/recurring-rules").session(memberSession).with(csrf())
                        .contentType("application/json").content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/recurring-rules").session(owner).with(csrf())
                        .contentType("application/json")
                        .content(valid.replace("\"accountId\":" + fixture.accountId(), "\"accountId\":" + Long.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不存在"));
        mvc.perform(post("/api/recurring-rules").session(owner).with(csrf())
                        .contentType("application/json")
                        .content(valid.replace("\"assignedUserId\":" + memberUser.getId(),
                                "\"assignedUserId\":" + Long.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.assignedUserId").value("分配用户不存在或未激活"));
    }

    @Test
    void patchRevalidatesAnUnchangedAccountThatBecameArchived() throws Exception {
        MockHttpSession owner = login();
        Fixture fixture = fixture();
        long ruleId = createRule(owner, monthlyBody(fixture, 3, "2026-03-03", null, false))
                .path("id").asLong();
        jdbc.update("update financial_accounts set archived_at=current_timestamp where id=?", fixture.accountId());

        mvc.perform(patch("/api/recurring-rules/{id}", ruleId).session(owner).with(csrf())
                        .contentType("application/json").content("{\"amount\":\"200.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.accountId").value("账户不存在"));
    }

    private JsonNode createRule(MockHttpSession session, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/recurring-rules").session(session).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private Fixture fixture() {
        AppUser owner = users.findByEmail("demo@local.family").orElseThrow();
        long householdId = owner.getHousehold().getId();
        return new Fixture(
                householdId,
                accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId).orElseThrow().getId(),
                categories.findByHouseholdIdOrderById(householdId).stream()
                        .filter(category -> category.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow().getId(),
                members.findByHouseholdIdOrderById(householdId).get(0).getId(),
                owner.getId());
    }

    private FamilyMember linkedMember(long householdId, long userId) {
        return members.findByHouseholdIdOrderById(householdId).stream()
                .filter(member -> member.getLinkedUser() != null && member.getLinkedUser().getId().equals(userId))
                .findFirst().orElseThrow();
    }

    private MockHttpSession join(MockHttpSession owner, String email) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType("application/json").content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = objectMapper.readTree(invite.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
                                {"email":"%s","displayName":"周期成员","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private MockHttpSession login() throws Exception {
        return login("demo", "demo1234");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String monthlyBody(
            Fixture f, int dayOfMonth, String startOn, String endOn, boolean paused) {
        return """
                {"kind":"EXPENSE","amount":"123.45","scheduleType":"MONTHLY","intervalValue":1,
                 "dayOfMonth":%d,"startOn":"%s",%s"accountId":%d,"memberId":%d,"categoryId":%d,
                 "assignedUserId":%d,"paused":%s}
                """.formatted(dayOfMonth, startOn,
                endOn == null ? "" : "\"endOn\":\"" + endOn + "\",",
                f.accountId(), f.memberId(), f.categoryId(), f.assignedUserId(), paused);
    }

    private static String weeklyBody(
            Fixture f, int interval, String dayOfWeek, String startOn, String endOn, boolean paused) {
        return """
                {"kind":"EXPENSE","amount":"123.45","scheduleType":"WEEKLY","intervalValue":%d,
                 "dayOfWeek":"%s","startOn":"%s",%s"accountId":%d,"memberId":%d,"categoryId":%d,
                 "assignedUserId":%d,"paused":%s}
                """.formatted(interval, dayOfWeek, startOn,
                endOn == null ? "" : "\"endOn\":\"" + endOn + "\",",
                f.accountId(), f.memberId(), f.categoryId(), f.assignedUserId(), paused);
    }

    record Fixture(long householdId, long accountId, long categoryId, long memberId, long assignedUserId) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock recurringTestClock() {
            return Clock.fixed(
                    Instant.parse("2026-03-31T02:00:00Z"),
                    ZoneId.of("Asia/Shanghai"));
        }
    }
}
