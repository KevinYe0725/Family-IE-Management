package com.familyfinance.loan;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

abstract class LoanAssetApiSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReadService ledger;
    MockHttpSession session;
    long household, member, user, category, account;

    @BeforeEach void setup() throws Exception {
        String email=register();
        session=login(email);
        user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);
        household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);
        member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
        category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
        fund("60000.00");
    }

    String register() throws Exception {
        String email=UUID.randomUUID()+"@loan-assets.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"displayName\":\"Loan\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Loan assets test\"}"))
            .andExpect(status().isCreated());
        return email;
    }
    MockHttpSession login(String email) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf())
            .param("username",email).param("password","loan-test-password"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    MockHttpSession joinMember() throws Exception {
        String token=data(mvc.perform(post("/api/family/invites").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"MEMBER\"}")).andExpect(status().isCreated()).andReturn()).path("token").asText();
        String email=UUID.randomUUID()+"@loan-assets.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"displayName\":\"Reader\",\"password\":\"loan-test-password\",\"mode\":\"JOIN\",\"inviteToken\":\""+token+"\"}"))
            .andExpect(status().isCreated());
        return login(email);
    }
    void fund(String amount) throws Exception {
        mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isOk());
    }
    String body(String mode) {
        return "{\"name\":\"Loan\",\"type\":\"OTHER\",\"linkedAssetId\":null,\"memberId\":"+member+",\"assignedUserId\":"+user
            +",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"150000.00\",\"annualRate\":0,\"termMonths\":10,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2025-12-31\",\"customSchedule\":null,\"fundingMode\":\""+mode
            +"\",\"accountingOn\":\"2026-01-01\",\"disbursementAccountId\":"+(mode.equals("DISBURSEMENT")?account:"null")
            +",\"createPurchasedAsset\":"+(mode.equals("FINANCED_PURCHASE")?"true":"null")+",\"disbursementAmount\":null}";
    }
    static String append(String body,String field) {return body.substring(0,body.length()-1)+","+field+"}";}
    ResultActions create(String body,String key) throws Exception {
        return mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    ResultActions patchLoan(long id,String body) throws Exception {
        return mvc.perform(patch("/api/loans/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    long asset(String type) throws Exception {
        String details=type.equals("PROPERTY")?",\"property\":{\"address\":\"Test address\",\"areaSqm\":\"80.00\",\"usageType\":\"自住\"}":"";
        return data(mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Asset "+UUID.randomUUID()+"\",\"type\":\""+type+"\",\"currentValue\":\"200000.00\",\"accountingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\""+details+"}"))
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    long loan(String principal) throws Exception {
        return data(create(body("OPENING").replace("150000.00",principal),UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    long count(String table) {return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
    Map<String,List<Map<String,Object>>> snapshot(String... tables) {
        var values=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:tables) values.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));
        return values;
    }
    Map<String,List<Map<String,Object>>> moneySnapshot() {
        return snapshot("ledger_journals","ledger_entries","ledger_accounts","ledger_sources","financial_transactions");
    }
    Map<String,List<Map<String,Object>>> allSnapshot() {
        return snapshot("loans","assets","asset_valuations","loan_installments","ledger_journals","ledger_entries","ledger_accounts","ledger_sources","financial_transactions","accounting_commands");
    }
    JsonNode data(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString()).path("data");}
}
