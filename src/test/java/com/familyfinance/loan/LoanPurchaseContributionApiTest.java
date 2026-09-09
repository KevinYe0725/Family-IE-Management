package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties="app.multicurrency.enabled=true") @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanPurchaseContributionApiTest extends LoanAssetApiSupport {
    @Test void fullPurchaseValueAndOwnCashShareOneJournalWithoutAFalseLoanReceipt() throws Exception {
        long journals=count("ledger_journals");
        var loan=data(create(purchase(),"purchase").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.purchaseValue").value("200000.00"))
            .andExpect(jsonPath("$.data.ownContribution").value("50000.00"))
            .andExpect(jsonPath("$.data.ownContributionAccountId").value(account))
            .andExpect(jsonPath("$.data.assetRelation").value("FINANCING"))
            .andExpect(jsonPath("$.data.linkedAssetName").value("其他资产1"))
            .andExpect(jsonPath("$.data.disbursementAmount").isEmpty()).andReturn());
        long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(20000000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(15000000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(1000000);
        assertThat(count("ledger_journals")).isEqualTo(journals+1);
        assertThat(count("financial_transactions")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type in ('ASSET_ACQUISITION','LOAN_DISBURSEMENT')",Long.class,household)).isZero();
        var entries=jdbc.queryForList("select e.account_code,e.debit_amount,e.credit_amount from ledger_entries e join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id where j.household_id=? and j.source_type='LOAN_FINANCED_PURCHASE' and j.source_id=? order by e.account_code",household,id);
        assertThat(entries).hasSize(3);
        assertThat(entries.get(1).get("account_code")).isEqualTo("CASH:"+account);
        assertThat((BigDecimal)entries.get(1).get("debit_amount")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal)entries.get(1).get("credit_amount")).isEqualByComparingTo("50000.00");
        mvc.perform(get("/api/assets/"+asset).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.purchaseValue").value("200000.00"))
            .andExpect(jsonPath("$.data.currentValue").value("200000.00"))
            .andExpect(jsonPath("$.data.initialValue").value("200000.00"))
            .andExpect(jsonPath("$.data.acquisitionSourceId").value(id));
        mvc.perform(get("/api/net-worth").session(session).param("asOf","2026-01-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("60000.00"));
        mvc.perform(get("/api/cash-movements").session(session).param("month","2026-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sourceType").value("LOAN_FINANCED_PURCHASE"))
            .andExpect(jsonPath("$.data.items[0].amount").value("50000.00"))
            .andExpect(jsonPath("$.data.items[0].description").value("贷款购置首付款"));
        assertThat(jdbc.queryForObject("select value_cents from asset_valuations where household_id=? and asset_id=?",Long.class,household,asset)).isEqualTo(20000000);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void insufficientContributionRollsBackLoanAssetValuationScheduleAndAllMoney() throws Exception {
        fund("49999.99");
        var before=allSnapshot();
        create(purchase(),"insufficient").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"149999.99","0","-1","200000.001","NaN",""})
    void incompleteOrInvalidFullPriceCannotInsertAPurchase(String value) throws Exception {
        var before=allSnapshot();
        create(purchase().replace("\"200000.00\"","\""+value+"\""),"invalid-price")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.purchaseValue").exists());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"MISSING","UNINITIALIZED","ARCHIVED","USD","OTHER_HOUSEHOLD"})
    void positiveContributionRequiresAConfirmedActiveCnyAccountInTheHousehold(String kind) throws Exception {
        Long target=null;
        if(kind.equals("OTHER_HOUSEHOLD")) {
            String foreign=register();
            target=jdbc.queryForObject("select a.id from financial_accounts a join app_users u on u.household_id=a.household_id where u.email=?",Long.class,foreign);
        } else if(!kind.equals("MISSING")) {
            target=data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Contribution\",\"type\":\"BANK\",\"currency\":\""+(kind.equals("USD")?"USD":"CNY")+"\",\"openingBalance\":\"60000.00\",\"openingOn\":\"2026-01-01\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
            if(kind.equals("ARCHIVED")) jdbc.update("update financial_accounts set archived_at=CURRENT_TIMESTAMP where id=?",target);
            if(kind.equals("UNINITIALIZED")) jdbc.update("update financial_accounts set opening_confirmed=false where id=?",target);
        }
        var before=allSnapshot();
        var result=create(purchase().replace("\"ownContributionAccountId\":"+account,"\"ownContributionAccountId\":"+target),"bad-contribution-account");
        if(kind.equals("UNINITIALIZED")) result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
        else if(kind.equals("ARCHIVED")) result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        else result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.ownContributionAccountId").exists());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"OPENING","DISBURSEMENT"})
    void nonpurchaseModesRejectPurchaseFieldsAndExposeNoContribution(String mode) throws Exception {
        for(String field:List.of("\"purchaseValue\":\"200000.00\"","\"ownContributionAccountId\":"+account)) {
            var before=allSnapshot();
            create(append(body(mode),field),"nonpurchase-invalid").andExpect(status().isBadRequest());
            assertThat(allSnapshot()).isEqualTo(before);
        }
        create(body(mode),"nonpurchase-valid").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.purchaseValue").isEmpty()).andExpect(jsonPath("$.data.ownContribution").value("0.00"))
            .andExpect(jsonPath("$.data.ownContributionAccountId").isEmpty()).andExpect(jsonPath("$.data.assetRelation").isEmpty())
            .andExpect(jsonPath("$.data.linkedAssetName").isEmpty());
    }

    @Test void zeroContributionNeedsNoAccountAndOldPurchaseRowsReadTheirOriginalPrincipal() throws Exception {
        for(String request:List.of(body("FINANCED_PURCHASE"),append(body("FINANCED_PURCHASE"),"\"purchaseValue\":\"150000.00\""))) {
            long id=data(create(request,java.util.UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.purchaseValue").value("150000.00")).andExpect(jsonPath("$.data.ownContribution").value("0.00"))
                .andExpect(jsonPath("$.data.ownContributionAccountId").isEmpty()).andReturn()).path("id").asLong();
            jdbc.update("update loans set purchase_value=null,asset_relation=null where id=?",id);
            var before=allSnapshot();
            mvc.perform(get("/api/loans/"+id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchaseValue").value("150000.00")).andExpect(jsonPath("$.data.ownContribution").value("0.00"))
                .andExpect(jsonPath("$.data.assetRelation").value("FINANCING"));
            assertThat(allSnapshot()).isEqualTo(before);
        }
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(6000000);
    }

    @Test void purchaseReplayCannotChangePriceContributionAccountOrRelation() throws Exception {
        long id=data(create(purchase(),"replay").andExpect(status().isCreated()).andReturn()).path("id").asLong();
        var before=allSnapshot();
        create(purchase(),"replay").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        for(String request:List.of(purchase().replace("200000.00","210000.00"),purchase().replace("\"ownContributionAccountId\":"+account,"\"ownContributionAccountId\":null"),append(purchase(),"\"assetRelation\":\"FINANCING\""),body("FINANCED_PURCHASE"))) {
            create(request,"replay").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(allSnapshot()).isEqualTo(before);
        patchLoan(id,"{\"principal\":\"160000.00\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PURCHASE_IMMUTABLE"));
        var money=moneySnapshot();
        patchLoan(id,"{\"annualRate\":0.05,\"termMonths\":12}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.purchaseValue").value("200000.00")).andExpect(jsonPath("$.data.ownContribution").value("50000.00"));
        assertThat(moneySnapshot()).isEqualTo(money);
    }

    @ParameterizedTest @ValueSource(strings={"FINANCED_PURCHASE","DISBURSEMENT"})
    void roundOneReceiptsReplayWithoutAcceptingAnyNewPurchaseField(String mode) throws Exception {
        String old=body(mode);
        if(mode.equals("DISBURSEMENT")) old=old.replace("\"disbursementAmount\":null","\"disbursementAmount\":\"149000.00\"");
        long id=data(create(old,"round-one").andExpect(status().isCreated()).andReturn()).path("id").asLong();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("LOAN_CREATE:"+user+":"+old).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("update accounting_commands set request_digest=? where household_id=? and request_key='round-one'",digest,household);
        var before=allSnapshot();
        create(old,"round-one").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        for(String field:List.of("\"purchaseValue\":\"150000.00\"","\"ownContributionAccountId\":"+account,"\"assetRelation\":\"FINANCING\"")) {
            create(append(old,field),"round-one").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(allSnapshot()).isEqualTo(before);
    }

    private String purchase() {return append(body("FINANCED_PURCHASE"),"\"purchaseValue\":\"200000.00\",\"ownContributionAccountId\":"+account);}
}
