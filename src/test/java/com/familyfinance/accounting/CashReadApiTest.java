package com.familyfinance.accounting;

import static com.familyfinance.accounting.LedgerAccountKind.CASH;
import static com.familyfinance.accounting.LedgerAccountKind.EQUITY;
import static com.familyfinance.accounting.LedgerAccountKind.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.fx.ExchangeRateBatch;
import com.familyfinance.fx.ExchangeRateStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.multicurrency.enabled=true", "app.seed.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CashReadApiTest.FixedClockConfiguration.class)
@Transactional
class CashReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeRateStore rates;
    @Autowired LedgerPostingService posting;
    MockHttpSession session;
    long household, user, member, income, expense, account;

    @BeforeEach
    void setup() throws Exception {
        String email = UUID.randomUUID() + "@cash-read.test";
        session = register(email);
        user = jdbc.queryForObject("select id from app_users where email=?", Long.class, email);
        household = jdbc.queryForObject("select household_id from app_users where id=?", Long.class, user);
        member = jdbc.queryForObject("select id from family_members where household_id=?", Long.class, household);
        income = jdbc.queryForObject("select min(id) from categories where household_id=? and kind='INCOME'", Long.class, household);
        expense = jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'", Long.class, household);
        account = jdbc.queryForObject("select id from financial_accounts where household_id=?", Long.class, household);
    }

    @Test
    void positionCountsCashChildrenOnceAtTheSameShanghaiTodayValuationAsNetWorth() throws Exception {
        fund("100.00");
        rates.save(new ExchangeRateBatch("ECB", LocalDate.of(2026, 9, 8),
                        Map.of("USD", new BigDecimal("7.00"), "HKD", new BigDecimal("0.90"))),
                Instant.parse("2026-09-08T14:00:00Z"));
        rates.save(new ExchangeRateBatch("ECB", LocalDate.of(2026, 9, 10),
                        Map.of("USD", new BigDecimal("8.00"), "HKD", BigDecimal.ONE)),
                Instant.parse("2026-09-10T14:00:00Z"));
        bank(session, "主卡", """
                [{"currency":"CNY","openingBalance":"100.00","openingOn":"2026-01-01"},
                 {"currency":"USD","openingBalance":"20.00","openingOn":"2026-01-01"}]
                """);
        loan("OPENING");

        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").value("2026-09-09"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.availableCash").value("340.00"))
                .andExpect(jsonPath("$.data.knownAvailableCash").value("340.00"))
                .andExpect(jsonPath("$.data.uninitializedCount").value(0))
                .andExpect(jsonPath("$.data.unconverted.length()").value(0));
        mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asset").value("340.00"))
                .andExpect(jsonPath("$.data.liability").value("2000.00"))
                .andExpect(jsonPath("$.data.netWorth").value("-1660.00"));
    }

    @Test
    void unknownOpeningKeepsTotalUnknownAndRetainsOnlyTheKnownSubtotal() throws Exception {
        cashAccount("已核对现金", "25.00");
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value(nullValue()))
                .andExpect(jsonPath("$.data.knownAvailableCash").value("25.00"))
                .andExpect(jsonPath("$.data.uninitializedCount").value(1))
                .andExpect(jsonPath("$.data.unconverted.length()").value(0));
    }

    @Test
    void legacyFutureDatedCashDoesNotEnterTodaysAvailablePosition() throws Exception {
        fund("100.00");
        long transaction = transaction("50.00", "2026-01-02");
        jdbc.update("""
                update ledger_journals set effective_on='2026-09-10'
                where household_id=? and source_type='TRANSACTION' and source_id=?
                """, household, transaction);
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("100.00"))
                .andExpect(jsonPath("$.data.knownAvailableCash").value("100.00"));
        mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asset").value("100.00"));
    }

    @Test
    void uninitializedNonCashLoanDoesNotBlockKnownCash() throws Exception {
        fund("100.00");
        long loan = loan("OPENING");
        jdbc.update("update loans set funding_mode=null, accounting_on=null where id=? and household_id=?", loan, household);
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("100.00"))
                .andExpect(jsonPath("$.data.uninitializedCount").value(0));
        mvc.perform(get("/api/cash-movements").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void cashReadsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/cash-position")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cash-movements")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingFxKeepsCashUnknownAndMovementsInNativeCurrency() throws Exception {
        fund("0.00");
        JsonNode bank = bank(session, "美元卡", """
                [{"currency":"USD","openingBalance":"20.00","openingOn":"2026-01-01"}]
                """);
        long usd = bank.path("accounts").get(0).path("id").asLong();
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value(nullValue()))
                .andExpect(jsonPath("$.data.knownAvailableCash").value("0.00"))
                .andExpect(jsonPath("$.data.uninitializedCount").value(0))
                .andExpect(jsonPath("$.data.unconverted.length()").value(1))
                .andExpect(jsonPath("$.data.unconverted[0].accountId").value(usd))
                .andExpect(jsonPath("$.data.unconverted[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.unconverted[0].nativeAmount").value("20.00"));
        mvc.perform(get("/api/cash-movements").session(session).param("accountId", String.valueOf(usd)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].amount").value("20.00"))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("CASH_OPENING"));
    }

    @Test
    void confirmedZeroForeignCashDoesNotRequireAnExchangeRate() throws Exception {
        fund("0.00");
        bank(session, "零余额卡", """
                [{"currency":"USD","openingBalance":"0.00","openingOn":"2026-01-01"}]
                """);
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("0.00"))
                .andExpect(jsonPath("$.data.unconverted.length()").value(0));
        mvc.perform(get("/api/cash-movements").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void loanReceiptAndRepaymentUseAuthoritativeSourcesWithoutDuplicatingTransactions() throws Exception {
        fund("0.00");
        long loan = loan("DISBURSEMENT");
        assertThat(count("financial_transactions")).isZero();
        long installment = jdbc.queryForObject("select min(id) from loan_installments where loan_id=?", Long.class, loan);
        mvc.perform(post("/api/loan-installments/{id}/confirm", installment).session(session).with(csrf())
                        .header("Idempotency-Key", "cash-read-payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidOn\":\"2026-01-03\"}"))
                .andExpect(status().isOk());

        JsonNode page = data(mvc.perform(get("/api/cash-movements").session(session).param("month", "2026-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("LOAN_PAYMENT"))
                .andExpect(jsonPath("$.data.items[0].kind").value("expense"))
                .andExpect(jsonPath("$.data.items[0].amount").value("1100.00"))
                .andExpect(jsonPath("$.data.items[1].sourceType").value("LOAN_DISBURSEMENT"))
                .andExpect(jsonPath("$.data.items[1].sourceId").value(loan))
                .andExpect(jsonPath("$.data.items[1].kind").value("income"))
                .andExpect(jsonPath("$.data.items[1].amount").value("2000.00"))
                .andExpect(jsonPath("$.data.items[1].internalTransfer").value(false)).andReturn());
        JsonNode receipt = page.path("items").get(1);
        assertThat(receipt.path("id").asText()).isNotBlank();
        assertThat(receipt.path("accountName").asText()).isNotBlank();
        assertThat(receipt.path("description").asText()).isNotBlank();
        mvc.perform(get("/api/accounting/history").session(session).param("sourceType", "LOAN_DISBURSEMENT")
                        .param("sourceId", String.valueOf(loan)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].journalId")
                        .value(receipt.path("journalId").asLong()));
        assertThat(count("financial_transactions")).isEqualTo(1);
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("900.00"));
    }

    @Test
    void correctionsShowOnlyCurrentCashLegAndDeletedSourcesDisappear() throws Exception {
        fund("0.00");
        long transaction = transaction("10.00", "2026-01-02");
        mvc.perform(patch("/api/transactions/{id}", transaction).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"15.00\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cash-movements").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceId").value(transaction))
                .andExpect(jsonPath("$.data.items[0].amount").value("15.00"));
        assertThat(count("ledger_journals")).isEqualTo(3);
        mvc.perform(delete("/api/transactions/{id}", transaction).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/cash-movements").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void transferDirectionsRemainInternalAndFiltersPaginateTheNativeAccountLegs() throws Exception {
        fund("100.00");
        long target = cashAccount("备用现金", "0.00");
        JsonNode bank = bank(session, "换汇卡", """
                [{"currency":"USD","openingBalance":"0.00","openingOn":"2026-01-01"}]
                """);
        long usd = bank.path("accounts").get(0).path("id").asLong();
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":%d,"toAccountId":%d,"amount":"10.00",
                                 "occurredOn":"2026-01-02","idempotencyKey":"cash-read-transfer"}
                                """.formatted(account, target)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/fx-transfers").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":%d,"toAccountId":%d,"fromAmount":"14.00","toAmount":"2.00",
                                 "fee":"1.00","occurredOn":"2026-01-03","idempotencyKey":"cash-read-fx"}
                                """.formatted(account, usd)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/cash-movements").session(session).param("accountId", String.valueOf(account))
                        .param("month", "2026-01").param("kind", "expense").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("FX_TRANSFER"))
                .andExpect(jsonPath("$.data.items[0].amount").value("15.00"))
                .andExpect(jsonPath("$.data.items[0].internalTransfer").value(true));
        mvc.perform(get("/api/cash-movements").session(session).param("accountId", String.valueOf(account))
                        .param("kind", "expense").param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("CASH_TRANSFER"))
                .andExpect(jsonPath("$.data.items[0].amount").value("10.00"))
                .andExpect(jsonPath("$.data.items[0].internalTransfer").value(true));
        mvc.perform(get("/api/cash-movements").session(session).param("bankAccountId", bank.path("id").asText())
                        .param("kind", "income"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].accountId").value(usd))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].amount").value("2.00"))
                .andExpect(jsonPath("$.data.items[0].internalTransfer").value(true));
        mvc.perform(get("/api/cash-movements").session(session).param("month", "2026-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void zeroNetCashLegsAndNonCashValuationsDoNotInventMovements() throws Exception {
        fund("0.00");
        posting.post(new LedgerPostingCommand(household, "CASH_READ_ZERO", 1, "cash-read-zero",
                LocalDate.of(2026, 1, 2), user, List.of(
                new LedgerEntryInput("CASH:" + account, CASH, 1000, 0, null, null),
                new LedgerEntryInput("CASH:" + account, CASH, 0, 1000, null, null))));
        posting.post(new LedgerPostingCommand(household, "ASSET_VALUATION", 1, "cash-read-noncash",
                LocalDate.of(2026, 1, 2), user, List.of(
                new LedgerEntryInput("EQUITY:OPENING", EQUITY, 1000, 0, null, null),
                new LedgerEntryInput("INCOME:VALUATION_GAIN", INCOME, 0, 1000, null, null))));
        mvc.perform(get("/api/cash-movements").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("0.00"));
    }

    @Test
    void foreignHouseholdIdsReturnNoCashDetailsAndCannotInflatePosition() throws Exception {
        fund("10.00");
        MockHttpSession other = register(UUID.randomUUID() + "@cash-foreign.test");
        JsonNode bank = bank(other, "外部家庭秘密卡", """
                [{"currency":"CNY","openingBalance":"999.00","openingOn":"2026-01-01"}]
                """);
        String foreignAccount = bank.path("accounts").get(0).path("id").asText();
        for (Map.Entry<String, String> filter : Map.of("accountId", foreignAccount,
                "bankAccountId", bank.path("id").asText()).entrySet()) {
            mvc.perform(get("/api/cash-movements").session(session).param(filter.getKey(), filter.getValue()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.items.length()").value(0));
        }
        mvc.perform(get("/api/cash-position").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCash").value("10.00"))
                .andExpect(jsonPath("$.data.uninitializedCount").value(0));
        String ownMovements = mvc.perform(get("/api/cash-movements").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(ownMovements).doesNotContain("外部家庭秘密卡", "999.00");
    }

    @Test
    void malformedFiltersAreRejectedAndPageBoundsAreSafe() throws Exception {
        fund("1.00");
        mvc.perform(get("/api/cash-movements").session(session).param("month", "2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.month").exists());
        mvc.perform(get("/api/cash-movements").session(session).param("kind", "transfer"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.kind").exists());
        mvc.perform(get("/api/cash-movements").session(session).param("page", "-1").param("size", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/api/cash-movements").session(session).param("page", "2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    private MockHttpSession register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","displayName":"Cash reader","password":"cash-read-password",
                 "mode":"CREATE","householdName":"Cash read test"}
                """.formatted(email))).andExpect(status().isCreated());
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", email).param("password", "cash-read-password"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void fund(String amount) throws Exception {
        mvc.perform(patch("/api/accounts/{id}", account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalance\":\"" + amount + "\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isOk());
    }

    private long cashAccount(String name, String amount) throws Exception {
        return data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"CASH","currency":"CNY",
                                 "openingBalance":"%s","openingOn":"2026-01-01"}
                                """.formatted(name, amount)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private JsonNode bank(MockHttpSession owner, String name, String balances) throws Exception {
        return data(mvc.perform(post("/api/bank-accounts").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"bankName\":\"银行\",\"balances\":" + balances + "}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private long loan(String mode) throws Exception {
        return data(mvc.perform(post("/api/loans").session(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"现金查询贷款","type":"OTHER","memberId":%d,"assignedUserId":%d,
                                 "paymentAccountId":%d,"paymentCategoryId":%d,"principal":"2000.00",
                                 "annualRate":0.1,"termMonths":2,"repaymentMethod":"CUSTOM","startOn":"2025-01-01",
                                 "fundingMode":"%s","accountingOn":"2026-01-01"%s,
                                 "customSchedule":[{"dueOn":"2026-01-02","principal":"1000.00","interest":"100.00"},
                                                   {"dueOn":"2026-02-02","principal":"1000.00","interest":"50.00"}]}
                                """.formatted(member, user, account, expense, mode,
                                mode.equals("DISBURSEMENT") ? ",\"disbursementAccountId\":" + account : "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long transaction(String amount, String day) throws Exception {
        return data(mvc.perform(post("/api/transactions").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind":"INCOME","amount":"%s","occurredOn":"%s",
                                 "accountId":%d,"memberId":%d,"categoryId":%d,"note":"现金查询"}
                                """.formatted(amount, day, account, member, income)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where household_id=?", Long.class, household);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean @Primary Clock cashReadClock() {
            return Clock.fixed(Instant.parse("2026-09-08T16:05:00Z"), ZoneOffset.UTC);
        }
    }
}
