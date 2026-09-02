package com.familyfinance.ledger.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.sql.Connection;
import java.sql.SQLException;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired RecurringRuleRepository rules;
    @MockitoSpyBean RecurringOccurrenceRepository occurrenceSpy;

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
            ch.qos.logback.classic.Logger sqlErrorLogger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger("org.hibernate.orm.jdbc.error");
            ch.qos.logback.classic.Level previousLevel = sqlErrorLogger.getLevel();
            try {
                sqlErrorLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
                Future<MvcResult> contender = executor.submit(() -> mvc.perform(
                                post("/api/recurring-occurrences/{id}/confirm", prepared.occurrenceId())
                                        .session(prepared.session()).with(csrf()))
                        .andReturn());
                MvcResult result = contender.get(5, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                        .path("error").path("code").asText()).isEqualTo("LOCK_RETRY_REQUIRED");
            } finally {
                sqlErrorLogger.setLevel(previousLevel);
            }
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void generatorAndArchiveSerializeOnRuleLockThenArchiveBulkCancelsEveryPendingOccurrence() throws Exception {
        PreparedRule prepared = prepareOwnerRule("90.00");
        CountDownLatch generatorHasRuleLock = new CountDownLatch(1);
        CountDownLatch allowGenerator = new CountDownLatch(1);
        BlockingOccurrenceInsertTrigger.install(jdbc, generatorHasRuleLock, allowGenerator);

        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Integer> generation = executor.submit(() -> {
                Thread.currentThread().setName("recurring-generator-archive");
                return recurringService.generateDueOccurrences();
            });
            assertThat(generatorHasRuleLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> archive = executor.submit(() -> {
                Thread.currentThread().setName("recurring-archive");
                return mvc.perform(delete("/api/recurring-rules/{id}", prepared.ruleId())
                                .session(prepared.session()).with(csrf()))
                        .andReturn();
            });

            awaitBlockedDatabaseSession();
            assertThat(archive.isDone()).as("archive must wait while generation owns the rule row").isFalse();
            allowGenerator.countDown();
            assertThat(generation.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(archive.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);

            assertThat(jdbc.queryForObject(
                    "select count(*) from recurring_occurrences where rule_id=? and status='PENDING'",
                    Long.class,
                    prepared.ruleId())).isZero();
            assertThat(jdbc.queryForObject(
                    "select active from recurring_rules where id=?", Boolean.class, prepared.ruleId())).isFalse();
            assertThat(jdbc.queryForObject(
                    "select next_due_on is null from recurring_rules where id=?",
                    Boolean.class,
                    prepared.ruleId())).isTrue();
            Mockito.verify(occurrenceSpy, Mockito.never())
                    .findByRuleIdOrderByDueOnAscIdAsc(prepared.ruleId());
        } finally {
            allowGenerator.countDown();
            executor.shutdownNow();
            BlockingOccurrenceInsertTrigger.remove(jdbc);
        }
    }

    @Test
    void generatorAndUpdateSerializeOnRuleLockWithoutLosingTheAdvancedCursor() throws Exception {
        PreparedRule prepared = prepareOwnerRule("91.00");
        CountDownLatch generatorHasRuleLock = new CountDownLatch(1);
        CountDownLatch allowGenerator = new CountDownLatch(1);
        BlockingOccurrenceInsertTrigger.install(jdbc, generatorHasRuleLock, allowGenerator);

        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Integer> generation = executor.submit(() -> {
                Thread.currentThread().setName("recurring-generator-update");
                return recurringService.generateDueOccurrences();
            });
            assertThat(generatorHasRuleLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> update = executor.submit(() -> {
                Thread.currentThread().setName("recurring-update");
                return mvc.perform(patch("/api/recurring-rules/{id}", prepared.ruleId())
                                .session(prepared.session()).with(csrf())
                                .contentType("application/json")
                                .content("{\"amount\":\"92.00\"}"))
                        .andReturn();
            });

            awaitBlockedDatabaseSession();
            assertThat(update.isDone()).as("update must wait while generation owns the rule row").isFalse();
            allowGenerator.countDown();
            assertThat(generation.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(update.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);

            assertThat(jdbc.queryForObject(
                    "select amount_cents from recurring_rules where id=?", Long.class, prepared.ruleId()))
                    .isEqualTo(9200L);
            assertThat(jdbc.queryForObject(
                    "select next_due_on from recurring_rules where id=?", java.time.LocalDate.class, prepared.ruleId()))
                    .isEqualTo(java.time.LocalDate.parse("2026-10-03"));
            assertThat(jdbc.queryForObject(
                    "select count(*) from recurring_occurrences where rule_id=? and due_on=date '2026-09-03'",
                    Long.class,
                    prepared.ruleId())).isEqualTo(1);
        } finally {
            allowGenerator.countDown();
            executor.shutdownNow();
            BlockingOccurrenceInsertTrigger.remove(jdbc);
        }
    }

    private PreparedOccurrence prepareOwnerOccurrence(String amount) throws Exception {
        PreparedRule prepared = prepareOwnerRule(amount);
        recurringService.generateDueOccurrences();
        return new PreparedOccurrence(
                prepared.session(), prepared.householdId(),
                occurrences.findByRuleIdOrderByDueOnAscIdAsc(prepared.ruleId()).get(0).getId());
    }

    private PreparedRule prepareOwnerRule(String amount) throws Exception {
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
        return new PreparedRule(owner, householdId, ruleId);
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

    private void awaitBlockedDatabaseSession() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long blocked = jdbc.queryForObject(
                    "select count(*) from information_schema.sessions where blocker_id is not null",
                    Long.class);
            if (blocked != null && blocked > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("concurrent recurring mutation never reached the database lock barrier");
    }

    record PreparedOccurrence(MockHttpSession session, long householdId, long occurrenceId) {}
    record PreparedRule(MockHttpSession session, long householdId, long ruleId) {}

    public static final class BlockingOccurrenceInsertTrigger implements org.h2.api.Trigger {
        private static volatile CountDownLatch entered;
        private static volatile CountDownLatch release;

        static void install(JdbcTemplate jdbc, CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
            entered = enteredLatch;
            release = releaseLatch;
            jdbc.execute("drop trigger if exists recurring_occurrence_insert_barrier");
            jdbc.execute("create trigger recurring_occurrence_insert_barrier before insert on recurring_occurrences "
                    + "for each row call 'com.familyfinance.ledger.recurring.RecurringConcurrencyTest$"
                    + "BlockingOccurrenceInsertTrigger'");
        }

        static void remove(JdbcTemplate jdbc) {
            jdbc.execute("drop trigger if exists recurring_occurrence_insert_barrier");
            entered = null;
            release = null;
        }

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            CountDownLatch currentEntered = entered;
            CountDownLatch currentRelease = release;
            if (currentEntered == null || currentRelease == null) return;
            currentEntered.countDown();
            try {
                if (!currentRelease.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("recurring occurrence insert barrier timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SQLException("recurring occurrence insert barrier interrupted", exception);
            }
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary Clock recurringConcurrencyClock() {
            return Clock.fixed(Instant.parse("2026-09-02T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
