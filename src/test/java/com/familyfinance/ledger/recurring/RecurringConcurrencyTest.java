package com.familyfinance.ledger.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(RecurringConcurrencyTest.FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecurringConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RecurringService recurringService;
    @Autowired RecurringOccurrenceRepository occurrences;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired FinancialAccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired FamilyMemberRepository members;
    @Autowired AppUserRepository users;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void concurrentConfirmationsReturnTheSameTransactionAndCommitOnlyOne() throws Exception {
        MockHttpSession owner = login();
        AppUser ownerUser = users.findByEmail("demo@local.family").orElseThrow();
        long householdId = ownerUser.getHousehold().getId();
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId)
                .orElseThrow().getId();
        long memberId = members.findByHouseholdIdOrderById(householdId).stream()
                .filter(member -> member.getLinkedUser() != null)
                .filter(member -> member.getLinkedUser().getId().equals(ownerUser.getId()))
                .findFirst().orElseThrow().getId();
        long categoryId = categories.findByHouseholdIdOrderById(householdId).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow().getId();
        MvcResult created = mvc.perform(post("/api/recurring-rules").session(owner).with(csrf())
                        .contentType("application/json").content("""
                                {"kind":"EXPENSE","amount":"88.00","scheduleType":"MONTHLY","intervalValue":1,
                                 "dayOfMonth":3,"startOn":"2026-09-03","accountId":%d,"memberId":%d,
                                 "categoryId":%d,"assignedUserId":%d,"paused":false}
                                """.formatted(accountId, memberId, categoryId, ownerUser.getId())))
                .andExpect(status().isCreated()).andReturn();
        long ruleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        recurringService.generateDueOccurrences();
        long occurrenceId = occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId).get(0).getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> atBarrier(ready, start, owner, occurrenceId));
            Future<MvcResult> second = executor.submit(() -> atBarrier(ready, start, owner, occurrenceId));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            List<Long> transactionIds = results.stream().map(result -> {
                try {
                    return objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("confirmedTransactionId").asLong();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(transactionIds.get(0)).isPositive().isEqualTo(transactionIds.get(1));
            assertThat(transactions.countBySourceTypeAndSourceId(TransactionSourceType.RECURRING, occurrenceId))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void occurrenceLockTimeoutReturnsTheSharedRetryableConflict() throws Exception {
        PreparedOccurrence prepared = prepareOwnerOccurrence("89.00");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                occurrences.findLockedByIdAndHouseholdId(prepared.occurrenceId(), prepared.householdId())
                        .orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> contender = executor.submit(() -> mvc.perform(
                            post("/api/recurring-occurrences/{id}/confirm", prepared.occurrenceId())
                                    .session(prepared.session()).with(csrf()))
                    .andReturn());
            MvcResult result = contender.get(5, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("LOCK_RETRY_REQUIRED");
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private PreparedOccurrence prepareOwnerOccurrence(String amount) throws Exception {
        MockHttpSession owner = login();
        AppUser ownerUser = users.findByEmail("demo@local.family").orElseThrow();
        long householdId = ownerUser.getHousehold().getId();
        long accountId = accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(householdId)
                .orElseThrow().getId();
        long memberId = members.findByHouseholdIdOrderById(householdId).stream()
                .filter(member -> member.getLinkedUser() != null)
                .filter(member -> member.getLinkedUser().getId().equals(ownerUser.getId()))
                .findFirst().orElseThrow().getId();
        long categoryId = categories.findByHouseholdIdOrderById(householdId).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE).findFirst().orElseThrow().getId();
        MvcResult created = mvc.perform(post("/api/recurring-rules").session(owner).with(csrf())
                        .contentType("application/json").content("""
                                {"kind":"EXPENSE","amount":"%s","scheduleType":"MONTHLY","intervalValue":1,
                                 "dayOfMonth":3,"startOn":"2026-09-03","accountId":%d,"memberId":%d,
                                 "categoryId":%d,"assignedUserId":%d,"paused":false}
                                """.formatted(amount, accountId, memberId, categoryId, ownerUser.getId())))
                .andExpect(status().isCreated()).andReturn();
        long ruleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        recurringService.generateDueOccurrences();
        return new PreparedOccurrence(
                owner, householdId, occurrences.findByRuleIdOrderByDueOnAscIdAsc(ruleId).get(0).getId());
    }

    private MvcResult atBarrier(
            CountDownLatch ready, CountDownLatch start, MockHttpSession session, long occurrenceId) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("confirmation barrier timed out");
        return mvc.perform(post("/api/recurring-occurrences/{id}/confirm", occurrenceId)
                        .session(session).with(csrf()))
                .andReturn();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    record PreparedOccurrence(MockHttpSession session, long householdId, long occurrenceId) {}

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary Clock recurringConcurrencyClock() {
            return Clock.fixed(Instant.parse("2026-09-02T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
