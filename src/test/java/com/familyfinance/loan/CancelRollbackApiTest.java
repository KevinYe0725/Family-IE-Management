package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.accounting.LedgerReportingService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class CancelRollbackApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReadService ledger; @Autowired LedgerReportingService reporting;
    MockHttpSession session; long household, account, category, member, user;

    @BeforeEach void setup() throws Exception {
        String email=UUID.randomUUID()+"@cancel.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Cancel\",\"password\":\"cancel-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Cancel test\"}")).andExpect(status().isCreated());
        session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","cancel-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);
        household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);
        member=jdbc.queryForObject("select min(id) from family_members where household_id=?",Long.class,household);
        category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
        fund("0.00");
    }

    // ---- 贷款取消：期初贷款不产生现金，冲销后负债归零 ----
    @Test void cancelOpeningLoanRemovesLiabilityWithoutCashMovement() throws Exception {
        long loan=createLoan("OPENING");
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(200000);
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(count("financial_transactions")).isZero();
        cancelLoan(loan,"opening").andExpect(status().isNoContent());
        assertThat(count("loans")).isZero();
        assertThat(count("loan_installments")).isZero();
        assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(count("financial_transactions")).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
        // 幂等重放
        cancelLoan(loan,"opening").andExpect(status().isNoContent());
    }

    // ---- 贷款取消：放款贷款取消后现金退回、borrowed 归零 ----
    @Test void cancelDisbursementLoanReturnsCashAndClearsBorrowed() throws Exception {
        long loan=createLoan("DISBURSEMENT");
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(200000);
        assertThat(flow().borrowed()).isEqualTo(200000);
        cancelLoan(loan,"disburse").andExpect(status().isNoContent());
        assertThat(count("loans")).isZero();
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(flow().borrowed()).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    // ---- 现金流关联：放款资金已被花掉时取消被拒绝，贷款保留 ----
    @Test void cancelDisbursementLoanBlockedWhenCashAlreadySpent() throws Exception {
        long loan=createLoan("DISBURSEMENT");
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"EXPENSE\",\"amount\":\"2000.00\",\"occurredOn\":\"2026-01-02\",\"accountId\":"+account+",\"memberId\":"+member+",\"categoryId\":"+category+"}")).andExpect(status().isCreated());
        cancelLoan(loan,"spent").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        assertThat(count("loans")).isEqualTo(1);
        assertThat(count("financial_transactions")).isEqualTo(1);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    // ---- 贷款购买物：取消贷款原子撤销关联资产与贷款 ----
    @Test void cancelFinancedPurchaseRemovesLoanAndPurchasedAssetAtomically() throws Exception {
        long loan=createLoan("FINANCED_PURCHASE");
        long asset=jdbc.queryForObject("select purchased_asset_id from loans where id=?",Long.class,loan);
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(200000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(200000);
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(flow().borrowed()).isEqualTo(200000);
        cancelLoan(loan,"financed").andExpect(status().isNoContent());
        assertThat(count("loans")).isZero();
        assertThat(count("assets")).isZero();
        assertThat(count("asset_valuations")).isZero();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isZero();
        assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();
        assertThat(flow().borrowed()).isZero();
        assertThat(count("financial_transactions")).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    // ---- 贷款取消：已有还款记录时禁止取消 ----
    @Test void cancelLoanWithPaymentIsRejected() throws Exception {
        fund("1100.00");
        long loan=createLoan("OPENING");
        var schedule=json.readTree(mvc.perform(get("/api/loans/"+loan+"/schedule").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        long installment=schedule.get(0).path("id").asLong();
        String due=schedule.get(0).path("dueOn").asString();
        mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf()).header("Idempotency-Key","pay").contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\""+due+"\"}")).andExpect(status().isOk());
        cancelLoan(loan,"paid").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_HAS_PAYMENTS"));
        assertThat(count("loans")).isEqualTo(1);
    }

    // ---- 资产取消：期初资产冲销后资产与期初权益归零 ----
    @Test void cancelOpeningAssetRestoresEquity() throws Exception {
        long asset=createOpeningAsset("期初收藏","3000.00");
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(300000);
        assertThat(ledger.balance(household,"EQUITY:OPENING")).isEqualTo(300000);
        cancelAsset(asset,"opening-asset").andExpect(status().isNoContent());
        assertThat(count("assets")).isZero();
        assertThat(count("asset_valuations")).isZero();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isZero();
        assertThat(ledger.balance(household,"EQUITY:OPENING")).isZero();
        assertThat(count("financial_transactions")).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
        cancelAsset(asset,"opening-asset").andExpect(status().isNoContent());
    }

    // ---- 资产取消：现金购入资产取消后现金退回、cashOut 归零 ----
    @Test void cancelPurchaseAssetReturnsCashAndClearsCashOut() throws Exception {
        fund("5000.00");
        long asset=createPurchaseAsset("购入收藏","2000.00","2000.00");
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(200000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(300000);
        assertThat(flow().cashOut()).isEqualTo(200000);
        cancelAsset(asset,"purchase-asset").andExpect(status().isNoContent());
        assertThat(count("assets")).isZero();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isZero();
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(500000);
        assertThat(flow().cashOut()).isZero();
        assertThat(count("financial_transactions")).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    // ---- 资产取消：有后续估值时禁止取消 ----
    @Test void cancelAssetWithLaterValuationIsRejected() throws Exception {
        fund("5000.00");
        long asset=createPurchaseAsset("购入收藏","2000.00","2000.00");
        mvc.perform(post("/api/assets/"+asset+"/valuations").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"valuedOn\":\"2026-01-05\",\"value\":\"2500.00\"}")).andExpect(status().isCreated());
        cancelAsset(asset,"valued").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_HAS_VALUATIONS"));
        assertThat(count("assets")).isEqualTo(1);
    }

    // ---- 资产取消：被贷款关联的资产禁止取消 ----
    @Test void cancelAssetReferencedByLoanIsRejected() throws Exception {
        long asset=createOpeningAsset("抵押房产","9000.00");
        mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"关联房贷\",\"type\":\"OTHER\",\"linkedAssetId\":"+asset+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"4000.00\",\"annualRate\":0.05,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}")).andExpect(status().isCreated());
        cancelAsset(asset,"linked").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
        assertThat(count("assets")).isEqualTo(1);
    }

    // ---- 资产取消：贷款购买物必须通过取消贷款一并撤销 ----
    @Test void cancelFinancedAssetIsRejectedAndDirectsToLoan() throws Exception {
        long loan=createLoan("FINANCED_PURCHASE");
        long asset=jdbc.queryForObject("select purchased_asset_id from loans where id=?",Long.class,loan);
        cancelAsset(asset,"financed-asset").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_FINANCED_CANCEL_VIA_LOAN"));
        assertThat(count("assets")).isEqualTo(1);
        assertThat(count("loans")).isEqualTo(1);
    }

    private LedgerReportingService.CashFlow flow(){return reporting.cashFlow(household,LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));}
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
    private void fund(String amount)throws Exception{mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());}
    private ResultActions cancelLoan(long id,String key)throws Exception{return mvc.perform(post("/api/loans/"+id+"/cancel").session(session).with(csrf()).header("Idempotency-Key",key));}
    private ResultActions cancelAsset(long id,String key)throws Exception{return mvc.perform(post("/api/assets/"+id+"/cancel").session(session).with(csrf()).header("Idempotency-Key",key));}
    private long createLoan(String mode)throws Exception{return data(mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(loanBody(mode))).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
    private String loanBody(String mode){
        return "{\"name\":\"取消贷款\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"2000.00\",\"annualRate\":0.1,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"fundingMode\":\""+mode+"\",\"accountingOn\":\"2026-01-01\""+(mode.equals("DISBURSEMENT")?",\"disbursementAccountId\":"+account:"")+(mode.equals("FINANCED_PURCHASE")?",\"createPurchasedAsset\":true":"")+"}";
    }
    private long createOpeningAsset(String name,String value)throws Exception{return createAsset(name,value,null);}
    private long createPurchaseAsset(String name,String purchase,String value)throws Exception{return createAsset(name,value,purchase);}
    private long createAsset(String name,String value,String purchase)throws Exception{
        String body="{\"name\":\""+name+"\",\"type\":\"OTHER\",\"accountingMode\":\""+(purchase==null?"OPENING":"PURCHASE")+"\",\"accountingOn\":\"2026-01-01\",\"currentValue\":\""+value+"\""+(purchase==null?"":",\"acquiredOn\":\"2026-01-01\",\"purchaseValue\":\""+purchase+"\",\"fundingAccountId\":"+account)+"}";
        return data(mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
