package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest(properties="app.multicurrency.enabled=true") @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanDisbursementAmountApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReadService ledger;
    MockHttpSession session;
    long household, member, user, category, account;

    @BeforeEach void setup() throws Exception {
        String email=register();
        session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf())
            .param("username",email).param("password","loan-test-password"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);
        household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);
        member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
        category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
    }

    @Test void withheldFeeSplitsCashExpenseAndLiabilityWithoutReducingContractPrincipal() throws Exception {
        fund("0.00");
        long loan=netLoan("net-receipt");

        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(980000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(1000000);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(20000);
        assertThat(jdbc.queryForObject("select disbursement_amount from loans where id=?",BigDecimal.class,loan))
            .isEqualByComparingTo("9800.00");
        assertThat(jdbc.queryForObject("select sum(principal_amount) from loan_installments where loan_id=?",BigDecimal.class,loan))
            .isEqualByComparingTo("10000.00");
        assertThat(count("financial_transactions")).isZero();
        mvc.perform(get("/api/net-worth").session(session).param("asOf","2026-01-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("-200.00"))
            .andExpect(jsonPath("$.data.liability").value("10000.00"));
        mvc.perform(get("/api/dashboard").session(session).param("month","2026-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.expense").value("0.00"))
            .andExpect(jsonPath("$.data.summary.income").value("0.00"));
        mvc.perform(get("/api/budgets/expense-summary").session(session).param("periodMonth","2026-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.expense").value("200.00"));
        mvc.perform(get("/api/cash-position").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.availableCash").value("9800.00"));
        mvc.perform(get("/api/cash-movements").session(session).param("month","2026-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].sourceType").value("LOAN_DISBURSEMENT"))
            .andExpect(jsonPath("$.data.items[0].amount").value("9800.00"));
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void omittedReceiptPersistsFullAmountAndLegacyNullReadsWithoutRewritingMoney() throws Exception {
        fund("0.00");
        long loan=data(create(body("DISBURSEMENT"),"full-receipt").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.disbursementAmount").value("10000.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("0.00")).andReturn()).path("id").asLong();
        assertThat(jdbc.queryForObject("select disbursement_amount from loans where id=?",BigDecimal.class,loan))
            .isEqualByComparingTo("10000.00");
        jdbc.update("update loans set disbursement_amount=null where id=?",loan);
        var before=snapshot();

        mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.disbursementAmount").value("10000.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("0.00"));
        assertThat(jdbc.queryForObject("select disbursement_amount from loans where id=?",BigDecimal.class,loan)).isNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(1000000);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"0","-1","10000.01","9800.001","NaN",""})
    void invalidReceiptCannotCreateAnyBusinessOrMoneyRecords(String amount) throws Exception {
        fund("0.00");
        var before=snapshot();
        create(append(body("DISBURSEMENT"),"\"disbursementAmount\":\""+amount+"\""),"invalid-receipt")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.disbursementAmount").exists());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"OPENING","FINANCED_PURCHASE"})
    void noncashFundingModesRejectReceiptAndReturnNoCashAmountWhenOmitted(String mode) throws Exception {
        fund("0.00");
        var before=snapshot();
        create(append(body(mode),"\"disbursementAmount\":\"9800.00\""),"noncash-receipt")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.disbursementAmount").exists());
        assertThat(snapshot()).isEqualTo(before);
        create(body(mode),"noncash-valid").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.disbursementAmount").isEmpty())
            .andExpect(jsonPath("$.data.withheldFee").value("0.00"));
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"UNINITIALIZED","ARCHIVED","USD","OTHER_HOUSEHOLD"})
    void receiptRequiresAnInitializedActiveCnyAccountInTheCurrentHousehold(String kind) throws Exception {
        if(!kind.equals("UNINITIALIZED")) fund("0.00");
        long target;
        if(kind.equals("UNINITIALIZED")) {
            target=account;
        } else if(kind.equals("OTHER_HOUSEHOLD")) {
            String foreign=register();
            target=jdbc.queryForObject("select a.id from financial_accounts a join app_users u on u.household_id=a.household_id where u.email=?",Long.class,foreign);
        } else {
            target=data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Receipt\",\"type\":\"BANK\",\"currency\":\""+(kind.equals("USD")?"USD":"CNY")+"\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
            if(kind.equals("ARCHIVED")) mvc.perform(delete("/api/accounts/"+target).session(session).with(csrf())).andExpect(status().isNoContent());
        }
        var before=snapshot();
        String request=append(body("DISBURSEMENT").replace("\"disbursementAccountId\":"+account,"\"disbursementAccountId\":"+target),"\"disbursementAmount\":\"9800.00\"");
        var result=create(request,"invalid-account");
        if(kind.equals("UNINITIALIZED")) result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
        else if(kind.equals("ARCHIVED")) result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        else result.andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void receiptReplayDoesNotPostTwiceAndCannotChangeOrOmitTheAmount() throws Exception {
        fund("0.00");
        long loan=netLoan("receipt-replay");
        var before=snapshot();
        create(append(body("DISBURSEMENT"),"\"disbursementAmount\":\"9800.00\""),"receipt-replay")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(loan))
            .andExpect(jsonPath("$.data.disbursementAmount").value("9800.00"));
        for(String request:List.of(body("DISBURSEMENT"),append(body("DISBURSEMENT"),"\"disbursementAmount\":\"9900.00\""))) {
            create(request,"receipt-replay").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"omitted","null","false","true"})
    void historicalCreateReceiptsReplayButNeverAbsorbNewAmountFields(String flag) throws Exception {
        fund("0.00");
        String old=body(flag.equals("true")?"FINANCED_PURCHASE":"DISBURSEMENT");
        if(flag.equals("omitted")) old=old.replace(",\"createPurchasedAsset\":null","");
        else old=old.replace("\"createPurchasedAsset\":null","\"createPurchasedAsset\":"+flag);
        long loan=data(create(old,"old-create").andExpect(status().isCreated()).andReturn()).path("id").asLong();
        // Literal pre-upgrade wire JSON, independent of the new DTO and production digest helpers.
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("LOAN_CREATE:"+user+":"+old).getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.update("update accounting_commands set request_digest=? where household_id=? and request_key='old-create'",digest,household)).isEqualTo(1);
        var before=snapshot();

        create(old,"old-create").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(loan));
        create(append(old,"\"disbursementAmount\":\"9800.00\""),"old-create")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select request_digest from accounting_commands where household_id=? and request_key='old-create'",String.class,household)).isEqualTo(digest);
    }

    @Test void feeLoanRejectsFinancialOriginationCorrectionsWithoutChangingScheduleOrCash() throws Exception {
        fund("0.00");
        long loan=netLoan("immutable-origin");
        var before=snapshot();
        for(String correction:List.of("{\"principal\":\"12000.00\"}","{\"accountingOn\":\"2026-01-02\"}","{\"disbursementAccountId\":"+account+"}")) {
            update(loan,correction).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAN_DISBURSEMENT_IMMUTABLE"));
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select principal_amount from loans where id=?",BigDecimal.class,loan)).isEqualByComparingTo("10000.00");
    }

    @Test void feeLoanPlanAndFuturePaymentMetadataCorrectionsKeepTheOriginalFeeJournal() throws Exception {
        fund("0.00");
        long loan=netLoan("plan-correction");
        long otherCategory=jdbc.queryForObject("select max(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        long journals=count("ledger_journals");
        long origin=jdbc.queryForObject("select current_journal_id from ledger_sources where household_id=? and source_type='LOAN_DISBURSEMENT' and source_id=?",Long.class,household,loan);
        update(loan,"{\"name\":\"Corrected name\",\"paymentCategoryId\":"+otherCategory+"}").andExpect(status().isOk());
        update(loan,"{\"annualRate\":0.12,\"termMonths\":5}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.principal").value("10000.00"))
            .andExpect(jsonPath("$.data.disbursementAmount").value("9800.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("200.00"));

        assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select sum(principal_amount) from loan_installments where loan_id=?",BigDecimal.class,loan)).isEqualByComparingTo("10000.00");
        assertThat(count("ledger_journals")).isEqualTo(journals);
        assertThat(jdbc.queryForObject("select current_journal_id from ledger_sources where household_id=? and source_type='LOAN_DISBURSEMENT' and source_id=?",Long.class,household,loan)).isEqualTo(origin);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(980000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(1000000);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(20000);
    }

    @Test void fullReceiptPrincipalCorrectionContinuesToAdjustCashWithoutInventingAFee() throws Exception {
        fund("0.00");
        long loan=data(create(body("DISBURSEMENT"),"full-correction").andExpect(status().isCreated()).andReturn()).path("id").asLong();
        update(loan,"{\"principal\":\"9000.00\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.disbursementAmount").value("9000.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("0.00"));
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(900000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(900000);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isZero();
    }

    @Test void scheduledPaymentAndPayoffRetainReceiptAndWithheldFee() throws Exception {
        fund("200.00");
        long loan=netLoan("repayment");
        long installment=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
        mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf())
            .header("Idempotency-Key","installment").contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-31\"}"))
            .andExpect(status().isOk());
        update(loan,"{\"name\":\"After repayment\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentPrincipal").value("9000.00"))
            .andExpect(jsonPath("$.data.disbursementAmount").value("9800.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("200.00"));
        JsonNode quote=data(mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-31"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAmount").value("9000.00")).andReturn());
        mvc.perform(post("/api/loans/"+loan+"/payoff").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"paidOn\":\"2026-01-31\",\"paymentAccountId\":"+account+",\"planToken\":\""+quote.path("planToken").asText()+"\",\"idempotencyKey\":\"payoff\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.disbursementAmount").value("9800.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("200.00"));
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(20000);
    }

    private String register() throws Exception {
        String email=UUID.randomUUID()+"@disbursement.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"displayName\":\"Loan\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Disbursement test\"}"))
            .andExpect(status().isCreated());
        return email;
    }
    private void fund(String amount) throws Exception {
        mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isOk());
    }
    private long netLoan(String key) throws Exception {
        return data(create(append(body("DISBURSEMENT"),"\"disbursementAmount\":\"9800.00\""),key)
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.principal").value("10000.00"))
            .andExpect(jsonPath("$.data.currentPrincipal").value("10000.00"))
            .andExpect(jsonPath("$.data.disbursementAmount").value("9800.00"))
            .andExpect(jsonPath("$.data.withheldFee").value("200.00")).andReturn()).path("id").asLong();
    }
    private String body(String mode) {
        return "{\"name\":\"Loan\",\"type\":\"OTHER\",\"linkedAssetId\":null,\"memberId\":"+member+",\"assignedUserId\":"+user
            +",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"10000.00\",\"annualRate\":0,\"termMonths\":10,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2025-12-31\",\"customSchedule\":null,\"fundingMode\":\""+mode
            +"\",\"accountingOn\":\"2026-01-01\",\"disbursementAccountId\":"+(mode.equals("DISBURSEMENT")?account:"null")+",\"createPurchasedAsset\":"+(mode.equals("FINANCED_PURCHASE")?"true":"null")+"}";
    }
    private static String append(String body,String field) {return body.substring(0,body.length()-1)+","+field+"}";}
    private ResultActions create(String body,String key) throws Exception {
        return mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions update(long loan,String body) throws Exception {
        return mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private long count(String table) {return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
    private List<Object> snapshot() {
        var values=new ArrayList<Object>();
        for(String table:List.of("loans","assets","financial_transactions","ledger_journals","ledger_sources","accounting_commands","loan_installments")) values.add(count(table));
        values.addAll(jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? order by account_code",BigDecimal.class,household));
        values.addAll(jdbc.queryForList("select id from loan_installments where household_id=? order by id",Long.class,household));
        return values;
    }
    private JsonNode data(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString()).path("data");}
}
