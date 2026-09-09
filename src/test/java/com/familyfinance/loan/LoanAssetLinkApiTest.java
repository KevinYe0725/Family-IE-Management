package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties="app.multicurrency.enabled=true") @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanAssetLinkApiTest extends LoanAssetApiSupport {
    @Test void twoFinancingLoansAndCollateralAppearOnceAndOnlyFinancingReducesReferenceEquity() throws Exception {
        long asset=asset("OTHER"),first=loan("60000.00"),second=loan("40000.00"),collateral=loan("25000.00");
        var before=moneySnapshot();
        String netWorth=data(mvc.perform(get("/api/net-worth").session(session).param("asOf","2026-01-01")).andExpect(status().isOk()).andReturn()).path("netWorth").asText();
        link(first,asset,"FINANCING",null,null,"link-first").andExpect(status().isOk());
        link(second,asset,"FINANCING",null,null,"link-second").andExpect(status().isOk());
        link(collateral,asset,"COLLATERAL",null,null,"link-collateral").andExpect(status().isOk());
        var result=data(mvc.perform(get("/api/assets/"+asset+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assetId").value(asset))
            .andExpect(jsonPath("$.data.financedPrincipal").value("100000.00"))
            .andExpect(jsonPath("$.data.referenceEquity").value("100000.00"))
            .andExpect(jsonPath("$.data.loans.length()").value(3)).andReturn());
        assertThat(result.path("loans").valueStream().map(n->n.path("loanId").asLong()).toList()).containsExactlyInAnyOrder(first,second,collateral);
        assertThat(result.path("loans").valueStream().map(n->n.path("originPurchase").asBoolean(true)).toList()).containsOnly(false);
        assertThat(result.path("loans").valueStream().filter(n->n.path("loanId").asLong()==collateral).findFirst().orElseThrow().path("relation").asText()).isEqualTo("COLLATERAL");
        assertThat(moneySnapshot()).isEqualTo(before);
        mvc.perform(get("/api/net-worth").session(session).param("asOf","2026-01-01")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.netWorth").value(netWorth));
    }

    @Test void replaceClearAndReplayUseExpectedLinksAndReturnTheCurrentLoan() throws Exception {
        long first=asset("OTHER"),second=asset("OTHER"),loan=loan("1000.00");
        var before=moneySnapshot();
        link(loan,first,"FINANCING",null,null,"attach").andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedAssetId").value(first));
        link(loan,second,"COLLATERAL",first,"FINANCING","replace").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL")).andExpect(jsonPath("$.data.linkedAssetId").value(second));
        long commands=count("accounting_commands");
        link(loan,first,"FINANCING",null,null,"attach").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.linkedAssetId").value(second)).andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL"));
        assertThat(count("accounting_commands")).isEqualTo(commands);
        link(loan,second,"COLLATERAL",null,null,"attach").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        link(loan,null,null,second,"COLLATERAL","clear").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.linkedAssetId").isEmpty()).andExpect(jsonPath("$.data.assetRelation").isEmpty())
            .andExpect(jsonPath("$.data.linkedAssetName").isEmpty());
        mvc.perform(get("/api/assets/"+second+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.financedPrincipal").value("0.00")).andExpect(jsonPath("$.data.loans.length()").value(0));
        assertThat(moneySnapshot()).isEqualTo(before);
    }

    @Test void staleExpectedAssetOrRelationCannotOverwriteANewerLink() throws Exception {
        long asset=asset("OTHER"),loan=loan("1000.00");
        link(loan,asset,"COLLATERAL",null,null,"attach").andExpect(status().isOk());
        var before=allSnapshot();
        link(loan,null,null,null,null,"stale-id").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_ASSET_LINK_CHANGED"));
        link(loan,null,null,asset,"FINANCING","stale-relation").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_ASSET_LINK_CHANGED"));
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @Test void collateralAcceptsDifferentAssetTypesAndSurvivesDefaultsAndPlanCorrections() throws Exception {
        long property=asset("PROPERTY");
        String request=append(body("OPENING").replace("\"linkedAssetId\":null","\"linkedAssetId\":"+property),"\"assetRelation\":\"COLLATERAL\"");
        long loan=data(create(request,"create-collateral").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL")).andReturn()).path("id").asLong();
        patchLoan(loan,"{\"name\":\"Corrected collateral\",\"linkedAssetId\":"+property+"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL"));
        patchLoan(loan,"{\"annualRate\":0.01,\"termMonths\":12}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL")).andExpect(jsonPath("$.data.linkedAssetId").value(property));
        mvc.perform(get("/api/assets/"+property+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.financedPrincipal").value("0.00")).andExpect(jsonPath("$.data.referenceEquity").value("200000.00"));
    }

    @Test void financingCompatibilityRemainsStrictWhileCollateralCanBeAttached() throws Exception {
        long property=asset("PROPERTY"),loan=loan("1000.00");
        var before=allSnapshot();
        link(loan,property,"FINANCING",null,null,"wrong-type").andExpect(status().isBadRequest());
        create(append(body("OPENING").replace("\"linkedAssetId\":null","\"linkedAssetId\":"+property),"\"assetRelation\":\"FINANCING\""),"wrong-create-type")
            .andExpect(status().isBadRequest());
        assertThat(allSnapshot()).isEqualTo(before);
        link(loan,property,"COLLATERAL",null,null,"collateral").andExpect(status().isOk()).andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL"));
    }

    @Test void legacyNullRelationReadsAsFinancingAndUsesThatEffectiveExpectedRelation() throws Exception {
        long asset=asset("OTHER");
        long loan=data(create(body("OPENING").replace("\"linkedAssetId\":null","\"linkedAssetId\":"+asset),"legacy-link")
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.assetRelation").value("FINANCING"));
        jdbc.update("update loans set asset_relation=null where id=?",loan);
        var before=allSnapshot();
        mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.assetRelation").value("FINANCING"));
        mvc.perform(get("/api/assets/"+asset+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.financedPrincipal").value("150000.00")).andExpect(jsonPath("$.data.referenceEquity").value("50000.00"));
        assertThat(allSnapshot()).isEqualTo(before);
        link(loan,asset,"COLLATERAL",asset,"FINANCING","reclassify").andExpect(status().isOk()).andExpect(jsonPath("$.data.assetRelation").value("COLLATERAL"));
    }

    @Test void purchasedAssetOriginCannotBeMovedClearedOrReclassified() throws Exception {
        long replacement=asset("OTHER");
        var loan=data(create(body("FINANCED_PURCHASE"),"purchase").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();
        mvc.perform(get("/api/assets/"+asset+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loans[0].originPurchase").value(true));
        var before=allSnapshot();
        for(String request:List.of(linkBody(replacement,"FINANCING",asset,"FINANCING"),linkBody(null,null,asset,"FINANCING"),linkBody(asset,"COLLATERAL",asset,"FINANCING"))) {
            putLink(id,request,UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PURCHASE_IMMUTABLE"));
        }
        create(append(body("FINANCED_PURCHASE"),"\"assetRelation\":\"COLLATERAL\""),"invalid-purchase-relation").andExpect(status().isBadRequest());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @Test void membersMayReadLinksButCannotChangeThemAndForeignResourcesAreIsolated() throws Exception {
        long asset=asset("OTHER"),loan=loan("1000.00");
        var memberSession=joinMember();
        var foreignSession=login(register());
        var before=allSnapshot();
        mvc.perform(get("/api/assets/"+asset+"/loans").session(memberSession)).andExpect(status().isOk());
        mvc.perform(put("/api/loans/"+loan+"/asset-link").session(memberSession).with(csrf()).header("Idempotency-Key","member-link")
            .contentType(MediaType.APPLICATION_JSON).content(linkBody(asset,"FINANCING",null,null))).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets/"+asset+"/loans").session(foreignSession)).andExpect(status().isNotFound());
        mvc.perform(put("/api/loans/"+loan+"/asset-link").session(foreignSession).with(csrf()).header("Idempotency-Key","foreign-link")
            .contentType(MediaType.APPLICATION_JSON).content(linkBody(asset,"FINANCING",null,null))).andExpect(status().isNotFound());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"OTHER_HOUSEHOLD","ARCHIVED"})
    void linkTargetsMustBeCurrentAssetsInTheSameHousehold(String kind) throws Exception {
        long loan=loan("1000.00"),target;
        if(kind.equals("OTHER_HOUSEHOLD")) {
            var previous=session;
            session=login(register());
            target=asset("OTHER");
            session=previous;
        } else {
            target=asset("OTHER");
            jdbc.update("update assets set status='ARCHIVED',archived_at=CURRENT_TIMESTAMP where id=?",target);
        }
        var before=allSnapshot();
        link(loan,target,"COLLATERAL",null,null,"bad-target").andExpect(status().isBadRequest());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @Test void linksRequireACommandKeyAndCompleteConsistentTargetPairs() throws Exception {
        long asset=asset("OTHER"),loan=loan("1000.00");
        var before=allSnapshot();
        mvc.perform(put("/api/loans/"+loan+"/asset-link").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(linkBody(asset,"FINANCING",null,null))).andExpect(status().isBadRequest());
        for(String request:List.of(linkBody(asset,null,null,null),linkBody(null,"FINANCING",null,null),linkBody(asset,"FINANCING",null,"COLLATERAL"))) {
            putLink(loan,request,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        }
        create(append(body("OPENING"),"\"assetRelation\":\"COLLATERAL\""),"relation-without-asset").andExpect(status().isBadRequest());
        assertThat(allSnapshot()).isEqualTo(before);
    }

    @Test void closedAndArchivedLoansStayVisibleButDoNotReduceReferenceEquity() throws Exception {
        long asset=asset("OTHER"),loan=loan("1000.00");
        link(loan,asset,"FINANCING",null,null,"link").andExpect(status().isOk());
        var quote=data(mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-01"))
            .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/loans/"+loan+"/payoff").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"paidOn\":\"2026-01-01\",\"paymentAccountId\":"+account+",\"planToken\":\""+quote.path("planToken").asText()+"\",\"idempotencyKey\":\"payoff\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/assets/"+asset+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.financedPrincipal").value("0.00")).andExpect(jsonPath("$.data.referenceEquity").value("200000.00"))
            .andExpect(jsonPath("$.data.loans[0].status").value("CLOSED")).andExpect(jsonPath("$.data.loans[0].remainingPrincipal").value("0.00"));
        mvc.perform(delete("/api/loans/"+loan).session(session).with(csrf())).andExpect(status().isNoContent());
        jdbc.update("update assets set status='ARCHIVED',archived_at=CURRENT_TIMESTAMP where id=?",asset);
        mvc.perform(get("/api/assets/"+asset+"/loans").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loans[0].status").value("ARCHIVED")).andExpect(jsonPath("$.data.financedPrincipal").value("0.00"));
    }

    private ResultActions link(long loan,Long asset,String relation,Long expected,String expectedRelation,String key) throws Exception {
        return putLink(loan,linkBody(asset,relation,expected,expectedRelation),key);
    }
    private ResultActions putLink(long loan,String body,String key) throws Exception {
        return mvc.perform(put("/api/loans/"+loan+"/asset-link").session(session).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private String linkBody(Long asset,String relation,Long expected,String expectedRelation) {
        return "{\"assetId\":"+asset+",\"relation\":"+(relation==null?"null":"\""+relation+"\"")
            +",\"expectedAssetId\":"+expected+",\"expectedRelation\":"+(expectedRelation==null?"null":"\""+expectedRelation+"\"")+"}";
    }
}
