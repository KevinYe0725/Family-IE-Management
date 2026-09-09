package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CashAccountingApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.familyfinance.ledger.recurring.RecurringService recurring;
    @Autowired java.time.Clock clock;
    MockHttpSession session;
    long household, member, expense, income, defaultAccount;

    @BeforeEach void fixture() throws Exception {
        String email=UUID.randomUUID()+"@cash.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"displayName\":\"Cash\",\"password\":\"cash-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Cash test\"}"))
            .andExpect(status().isCreated());
        session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","cash-test-password"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        household=jdbc.queryForObject("select household_id from app_users where email=?",Long.class,email);
        member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
        expense=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        income=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='INCOME'",Long.class,household);
        defaultAccount=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
    }

    @Test void specializingAccountsCannotInitializeOrBypassInsufficientFunds() throws Exception {
        mvc.perform(patch("/api/accounts/"+defaultAccount).session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"type":"WALLET","walletProvider":"ALIPAY"}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.openingConfirmed").value(false));
        writeTransaction(defaultAccount,"INCOME","10.00","still-unconfirmed").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
        long id=create("specialized","10.00");
        writeTransaction(id,"EXPENSE","4.00","before-specialization").andExpect(status().isCreated());
        mvc.perform(patch("/api/accounts/"+id).session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"type":"WALLET","walletProvider":"WECHAT"}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value("6.00"));
        writeTransaction(id,"EXPENSE","6.01","after-specialization").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(get("/api/accounts/"+id).session(session))
            .andExpect(jsonPath("$.data.balance").value("6.00"));
    }

    @Test void automaticDefaultRequiresExplicitOpeningEvenForIncome() throws Exception {
        mvc.perform(get("/api/accounts/"+defaultAccount).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.openingConfirmed").value(false)).andExpect(jsonPath("$.data.balance").doesNotExist());
        writeTransaction(defaultAccount,"INCOME","10.00","unconfirmed").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
    }
    @Test void unconfirmedDefaultCannotBeArchivedIntoAnUnrecoverableReportGate() throws Exception {
        mvc.perform(delete("/api/accounts/"+defaultAccount).session(session).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
        mvc.perform(get("/api/accounts").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(defaultAccount)).andExpect(jsonPath("$.data.items[0].openingConfirmed").value(false));
        mvc.perform(patch("/api/accounts/"+defaultAccount).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.openingConfirmed").value(true));
        mvc.perform(delete("/api/accounts/"+defaultAccount).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("0.00"));
    }
    @Test void explicitOpeningIsJournalBackedAndZeroExpenseRollsBack() throws Exception {
        long account=create("zero","0.00");
        writeTransaction(account,"EXPENSE","1.00","expense").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        assertThat(jdbc.queryForObject("select count(*) from financial_transactions where household_id=?",Long.class,household)).isZero();
        long funded=create("funded","10.00");
        mvc.perform(get("/api/accounts/"+funded).session(session)).andExpect(jsonPath("$.data.balance").value("10.00"));
    }
    @Test void insufficientFundsNamesOwnedAccountAndHistoricalDeficitInYuan() throws Exception {
        long a=create("工资卡","0.00");
        var zero=writeTransaction(a,"EXPENSE","1100.01","zero").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS")).andReturn();
        assertThat(mapper.readTree(zero.getResponse().getContentAsString()).path("error").path("message").asText())
            .contains("工资卡","2026-01-02","¥1100.01","当前账内余额 ¥0.00").doesNotContain("CASH:"," 分");
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"INCOME\",\"amount\":\"2000.00\",\"occurredOn\":\"2026-01-04\",\"accountId\":"+a+",\"memberId\":"+member+",\"categoryId\":"+income+"}"))
            .andExpect(status().isCreated());
        var historical=writeTransaction(a,"EXPENSE","1100.01","history").andExpect(status().isConflict()).andReturn();
        assertThat(mapper.readTree(historical.getResponse().getContentAsString()).path("error").path("message").asText())
            .contains("2026-01-02","该日资金缺口 ¥1100.01","当前账内余额 ¥2000.00").doesNotContain("当前余额不足");
    }
    @Test void exactPaymentAndIncomeDeletionRespectCashAndIdempotency() throws Exception {
        long a=create("wallet","0.00");
        var first=writeTransaction(a,"INCOME","10.00","income").andExpect(status().isCreated()).andReturn();
        long id=mapper.readTree(first.getResponse().getContentAsString()).path("data").path("id").asLong();
        writeTransaction(a,"INCOME","10.00","income").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        writeTransaction(a,"INCOME","11.00","income").andExpect(status().isConflict());
        writeTransaction(a,"EXPENSE","10.00","spend").andExpect(status().isCreated());
        mvc.perform(delete("/api/transactions/"+id).session(session).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("0.00"));
    }
    @Test void transferFundsOnlySelectedAccountsAndReplaysExactlyOnce() throws Exception {
        long a=create("a","0.00"), b=create("b","100.00");
        writeTransaction(a,"EXPENSE","10.00","before").andExpect(status().isConflict());
        String body="{\"fromAccountId\":"+b+",\"toAccountId\":"+a+",\"amount\":\"10.00\",\"occurredOn\":\"2026-01-02\",\"idempotencyKey\":\"transfer\"}";
        for(int i=0;i<2;i++) mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        writeTransaction(a,"EXPENSE","10.00","after").andExpect(status().isCreated());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("0.00"));
        mvc.perform(get("/api/accounts/"+b).session(session)).andExpect(jsonPath("$.data.balance").value("90.00"));
    }
    @Test void transferHistoryHasCompleteHouseholdScopedPaginationHeaders() throws Exception {
        long a=create("pagination-from","20.00"), b=create("pagination-to","0.00");
        for(int i=0;i<2;i++) mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAccountId\":"+a+",\"toAccountId\":"+b+",\"amount\":\"1.00\",\"occurredOn\":\"2026-01-02\",\"idempotencyKey\":\"page-"+i+"\"}"))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/transfers?page=0&size=1").session(session)).andExpect(status().isOk())
            .andExpect(header().string("X-Page","0")).andExpect(header().string("X-Page-Size","1"))
            .andExpect(header().string("X-Total-Elements","2")).andExpect(header().string("X-Total-Pages","2"))
            .andExpect(header().string("X-Has-Next","true")).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/transfers?page=1&size=1").session(session)).andExpect(status().isOk())
            .andExpect(header().string("X-Page","1")).andExpect(header().string("X-Has-Next","false"));
        mvc.perform(get("/api/transfers?page=-1&size=500").session(session)).andExpect(status().isOk())
            .andExpect(header().string("X-Page","0")).andExpect(header().string("X-Page-Size","50"));
    }
    @Test void openingCorrectionsAuditZeroCyclesAndProtectSpentMoney() throws Exception {
        long a=create("audit","0.00");
        for(String amount:new String[]{"10.00","0.00","20.00"}) {
            mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"openingBalance\":\""+amount+"\"}")).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select count(*) from cash_opening_events where account_id=?",Long.class,a)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=?",Long.class,household)).isEqualTo(3);
        writeTransaction(a,"EXPENSE","20.00","all").andExpect(status().isCreated());
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\"0.00\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.openingBalance").value("20.00"));
    }
    @Test void futureDatesAndNonzeroArchiveAreRejected() throws Exception {
        long a=create("funded","10.00");
        mvc.perform(delete("/api/accounts/"+a).session(session).with(csrf())).andExpect(status().isConflict());
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingOn\":\"9999-01-01\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"INCOME\",\"amount\":\"100\",\"occurredOn\":\"9999-01-01\",\"accountId\":"+a+",\"memberId\":"+member+",\"categoryId\":"+income+"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void generatedFieldsAreProtectedWhileMetadataCanBeEdited() throws Exception {
        long a=create("cash","10.00");
        long id=mapper.readTree(writeTransaction(a,"EXPENSE","1.00","generated").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        jdbc.update("update financial_transactions set source_type='RECURRING',source_id=? where id=?",Long.MAX_VALUE-id,id);
        mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"2.00\"}"))
            .andExpect(status().isConflict());
        mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"metadata\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.note").value("metadata"));
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("9.00"));
    }
    @Test void reversedManualSourceCannotBeRecreatedAndItsCategoryKindCannotChange() throws Exception {
        long a=create("cash","10.00");
        long id=mapper.readTree(writeTransaction(a,"EXPENSE","1.00","expense").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(delete("/api/transactions/"+id).session(session).with(csrf())).andExpect(status().isNoContent());
        writeTransaction(a,"EXPENSE","1.00","expense").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_SOURCE_REVERSED"));
        mvc.perform(patch("/api/categories/"+expense).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"INCOME\",\"name\":\"changed\",\"color\":\"#000000\"}")).andExpect(status().isConflict());
    }
    @Test void overdueRecurringUsesActualConfirmationDateAndInsufficientFundsStaysPending() throws Exception {
        long a=create("recurring","0.00");
        long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
        var created=mvc.perform(post("/api/recurring-rules").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"EXPENSE\",\"amount\":\"10.00\",\"scheduleType\":\"MONTHLY\",\"intervalValue\":1,\"dayOfMonth\":2,\"startOn\":\"2026-01-02\",\"endOn\":\"2026-01-02\",\"accountId\":"+a+",\"memberId\":"+member+",\"categoryId\":"+expense+",\"assignedUserId\":"+actor+",\"paused\":false}"))
            .andExpect(status().isCreated()).andReturn();
        long rule=mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        recurring.generateDueOccurrences();
        long occurrence=jdbc.queryForObject("select id from recurring_occurrences where rule_id=?",Long.class,rule);
        mvc.perform(post("/api/recurring-occurrences/"+occurrence+"/confirm").session(session).with(csrf()).contentType("application/json").content(com.familyfinance.ledger.recurring.RecurringReviewFixture.body(mvc,session,occurrence,null))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from recurring_occurrences where id=?",String.class,occurrence)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from financial_transactions where household_id=?",Long.class,household)).isZero();
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\"10.00\",\"openingOn\":\"2026-01-03\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/recurring-occurrences/"+occurrence+"/confirm").session(session).with(csrf()).contentType("application/json").content(com.familyfinance.ledger.recurring.RecurringReviewFixture.body(mvc,session,occurrence,null))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select occurred_on from financial_transactions where household_id=?",java.sql.Date.class,household).toLocalDate())
            .isEqualTo(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))));
        assertThat(jdbc.queryForObject("select due_on from recurring_occurrences where id=?",java.sql.Date.class,occurrence).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026,1,2));
        mvc.perform(post("/api/accounts").session(session).with(csrf()).header("Idempotency-Key","recurring:"+occurrence).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"collision\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void recurringCannotReuseAKeyFromMetadataWithoutJournal() throws Exception {
        long a=create("key","10.00");
        long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
        var created=mvc.perform(post("/api/recurring-rules").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"EXPENSE\",\"amount\":\"10.00\",\"scheduleType\":\"MONTHLY\",\"intervalValue\":1,\"dayOfMonth\":2,\"startOn\":\"2026-01-02\",\"endOn\":\"2026-01-02\",\"accountId\":"+a+",\"memberId\":"+member+",\"categoryId\":"+expense+",\"assignedUserId\":"+actor+",\"paused\":false}"))
            .andExpect(status().isCreated()).andReturn();
        long rule=mapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        recurring.generateDueOccurrences();
        long occurrence=jdbc.queryForObject("select id from recurring_occurrences where rule_id=?",Long.class,rule);
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).header("Idempotency-Key","recurring:"+occurrence).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"renamed\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/recurring-occurrences/"+occurrence+"/confirm").session(session).with(csrf()).contentType("application/json").content(com.familyfinance.ledger.recurring.RecurringReviewFixture.body(mvc,session,occurrence,null)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select status from recurring_occurrences where id=?",String.class,occurrence)).isEqualTo("PENDING");
    }
    @Test void manualCorrectionReplacesJournalAndPreservesOriginalAudit() throws Exception {
        long a=create("correct","10.00");
        long id=mapper.readTree(writeTransaction(a,"EXPENSE","1.00","create").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        for(int i=0;i<2;i++) mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).header("Idempotency-Key","correct").contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"3.00\",\"note\":\"correction\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("7.00"));
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=? and source_type='TRANSACTION' and source_id=?",Long.class,household,id)).isEqualTo(3);
    }
    @Test void transferRejectsSameAccountCrossFamilyAndMemberWritesButAllowsMemberRead() throws Exception {
        long a=create("a","10.00");
        String body="{\"fromAccountId\":"+a+",\"toAccountId\":"+a+",\"amount\":\"1.00\",\"occurredOn\":\"2026-01-02\",\"idempotencyKey\":\"same\"}";
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        MockHttpSession firstSession=session;long firstHousehold=household;
        fixture();long foreign=create("foreign","0.00");session=firstSession;
        String cross="{\"fromAccountId\":"+a+",\"toAccountId\":"+foreign+",\"amount\":\"1.00\",\"occurredOn\":\"2026-01-02\",\"idempotencyKey\":\"cross\"}";
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cross)).andExpect(status().isNotFound());
        jdbc.update("update household_memberships set role='MEMBER' where household_id=?",firstHousehold);
        mvc.perform(get("/api/transfers").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void archivedOriginalCashCannotBeRestoredByDeleteOrAccountReassignment(boolean deleteOriginal) throws Exception {
        long a=create("archived","10.00"),b=create("active","10.00");
        long id=mapper.readTree(writeTransaction(a,"EXPENSE","10.00","spend").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(delete("/api/accounts/"+a).session(session).with(csrf())).andExpect(status().isNoContent());
        if(deleteOriginal) mvc.perform(delete("/api/transactions/"+id).session(session).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        else mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":"+b+"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"history metadata\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("0.00")).andExpect(jsonPath("$.data.archivedAt").exists());
        mvc.perform(get("/api/accounts/"+b).session(session)).andExpect(jsonPath("$.data.balance").value("10.00"));
        assertThat(jdbc.queryForObject("select account_id from financial_transactions where id=?",Long.class,id)).isEqualTo(a);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0.00","10.00"})
    void openingDateBlocksPriorIncomeButAllowsSameDayForZeroAndPositiveOpenings(String amount) throws Exception {
        long a=create("boundary",amount);
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingOn\":\"2026-01-03\"}"))
            .andExpect(status().isOk());
        writeTransaction(a,"INCOME","1.00","before-opening").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ACTIVITY_BEFORE_OPENING"));
        writeTransactionOn(a,"INCOME","1.00","same-day","2026-01-03").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from financial_transactions where household_id=?",Long.class,household)).isEqualTo(1);
    }
    @Test void transferChecksOpeningDateOnBothCashLegs() throws Exception {
        long early=create("early","10.00"),late=create("late","10.00");
        mvc.perform(patch("/api/accounts/"+late).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingOn\":\"2026-01-03\"}"))
            .andExpect(status().isOk());
        for(long[] pair:new long[][]{{early,late},{late,early}}) {
            String body="{\"fromAccountId\":"+pair[0]+",\"toAccountId\":"+pair[1]+",\"amount\":\"1.00\",\"occurredOn\":\"2026-01-02\",\"idempotencyKey\":\"before-"+pair[0]+"\"}";
            mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ACTIVITY_BEFORE_OPENING"));
        }
        assertThat(jdbc.queryForObject("select count(*) from cash_transfers where household_id=?",Long.class,household)).isZero();
    }
    @Test void openingCorrectionRespectsOnlyCurrentEffectiveActivity() throws Exception {
        long a=create("date","0.00");
        long id=mapper.readTree(writeTransaction(a,"INCOME","10.00","income").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingOn\":\"2026-01-03\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("OPENING_DATE_AFTER_ACTIVITY"));
        mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"occurredOn\":\"2026-01-03\"}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingOn\":\"2026-01-03\"}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/transactions/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"occurredOn\":\"2026-01-02\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ACTIVITY_BEFORE_OPENING"));
        mvc.perform(delete("/api/transactions/"+id).session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(patch("/api/accounts/"+a).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingOn\":\"2026-01-04\"}"))
            .andExpect(status().isOk());
    }
    long create(String name,String amount) throws Exception {
        var r=mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\""+name+"\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    org.springframework.test.web.servlet.ResultActions writeTransaction(long account,String kind,String amount,String key) throws Exception {
        return writeTransactionOn(account,kind,amount,key,"2026-01-02");
    }
    org.springframework.test.web.servlet.ResultActions writeTransactionOn(long account,String kind,String amount,String key,String day) throws Exception {
        return mvc.perform(post("/api/transactions").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\""+kind+"\",\"amount\":\""+amount+"\",\"occurredOn\":\""+day+"\",\"accountId\":"+account+",\"memberId\":"+member+",\"categoryId\":"+(kind.equals("INCOME")?income:expense)+"}"));
    }
}
