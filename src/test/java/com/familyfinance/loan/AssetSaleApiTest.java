package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.TransactionSourceType;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class AssetSaleApiTest extends LoanAssetApiSupport {
    @MockitoSpyBean LoanAccountingService accounting;
    @MockitoSpyBean LoanPlanToken plans;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void payoffPreviewRejectsMissingEffectiveAccountingMemberBeforeApprovingSale() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        jdbc.update("update loans set member_id=null,assigned_user_id=null where id=?",loan);var before=allSnapshot();
        preview(asset,draft("DIRECT","15000.00","0.00",account,account,payoffSelection(loan),false,"2026-01-01"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STALE_REFERENCE"));
        assertThat(allSnapshot()).isEqualTo(before);
    }
    @Test void changedFallbackAccountingMemberInvalidatesParentPayoffQuote() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");jdbc.update("update loans set member_id=null where id=?",loan);
        String draft=draft("DIRECT","15000.00","0.00",account,account,payoffSelection(loan),false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());
        jdbc.update("update family_members set linked_user_id=null where id=?",member);
        jdbc.update("insert into family_members(household_id,linked_user_id,name,role_label,created_at) values(?,?,'Replacement','Member',CURRENT_TIMESTAMP)",household,user);
        var before=completeSnapshot();sell(asset,confirmation(draft,q),"member-changed").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("ASSET_SALE_PLAN_CHANGED"));assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void actualPayoffInterestCannotUnderstateDueInterestAndDoesNotChangeDisposalBookGain() throws Exception {
        long asset=asset("OTHER"),loan=dueLoan(asset);fund("0.00");
        String selected="[{\"loanId\":"+loan+",\"mode\":\"PAYOFF\",\"interestAmount\":\"99.00\"}]";
        String draft=draft("DIRECT","15000.00","100.00",account,null,selected,false,"2026-01-31");
        preview(asset,draft).andExpect(status().isBadRequest());draft=draft.replace("99.00","120.00");
        var receipt=confirm(asset,draft);
        assertThat(receipt.path("preview").path("totalInterest").asText()).isEqualTo("120.00");
        assertThat(receipt.path("preview").path("bookGain").asText()).isEqualTo("-185100.00");
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(12000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(478000);
        mvc.perform(get("/api/assets/"+asset).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.disposalBookGain").value("-185100.00"));
    }
    @Test void zeroProceedsCanRetainDebtWithoutInventingAnAccountMovement() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        var receipt=confirm(asset,draft("VIA_ACCOUNT","0.00","0.00",null,null,"[]",true,"2026-01-01"));
        assertThat(receipt.path("preview").path("balances").size()).isZero();
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(1000000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(6000000);
        assertThat(jdbc.queryForObject("select linked_asset_id from loans where id=?",Long.class,loan)).isEqualTo(asset);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"VIA_ACCOUNT","DIRECT"})
    void failureAfterFirstLoanReallySettlesRollsBackAssetCashLoansAndEveryReceipt(String route) throws Exception {
        long asset=asset("OTHER"),first=linkedLoan(asset,"10000.00"),second=linkedLoan(asset,"10000.00");fund("0.00");
        String selected="[{\"loanId\":"+first+",\"mode\":\"PAYOFF\"},{\"loanId\":"+second+",\"mode\":\"PAYOFF\"}]";
        String draft=draft(route,"30000.00","0.00",account,account,selected,false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());var before=completeSnapshot();
        var witnessed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            Loan child=invocation.getArgument(0);
            if(child.getId()==second){
                assertThat(jdbc.queryForObject("select current_principal_amount from loans where id=?",BigDecimal.class,first)).isEqualByComparingTo("0.00");
                assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=? and source_type='LOAN_PREPAYMENT'",Long.class,household)).isEqualTo(1);
                assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(route.equals("DIRECT")?1000000:2000000);
                witnessed.set(true);throw new ResourceConflictException("TEST_SECOND_LOAN","Injected failure after first loan settled");
            }
            return invocation.callRealMethod();
        }).when(accounting).pay(any(Loan.class),any(FinancialTransaction.class),any(BigDecimal.class),any(BigDecimal.class),anyString());
        sell(asset,confirmation(draft,q),"second-loan").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEST_SECOND_LOAN"));
        assertThat(witnessed).isTrue();assertThat(completeSnapshot()).isEqualTo(before);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"VIA_ACCOUNT","DIRECT"})
    void extraPrincipalFailureRestoresAlreadyPaidDueRowAndNotifications(String route) throws Exception {
        long asset=asset("OTHER"),loan=dueLoan(asset);fund("0.00");
        jdbc.update("insert into notifications(household_id,user_id,type,title,reference_type,reference_id) select household_id,?,'LOAN_DUE','Due fixture','LOAN_INSTALLMENT',id from loan_installments where loan_id=?",user,loan);
        String selected="[{\"loanId\":"+loan+",\"mode\":\"PARTIAL\",\"additionalPrincipal\":\"3000.00\"}]";
        String draft=draft(route,"5000.00","100.00",account,account,selected,false,"2026-01-31");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());var before=completeSnapshot();
        var witnessed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            FinancialTransaction tx=invocation.getArgument(1);
            if(tx.getSourceType()==TransactionSourceType.LOAN_PREPAYMENT){
                assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from notifications where household_id=? and resolved_at is not null",Long.class,household)).isEqualTo(1);
                witnessed.set(true);throw new ResourceConflictException("TEST_EXTRA_CHILD","Injected extra failure");
            }
            return invocation.callRealMethod();
        }).when(accounting).pay(any(Loan.class),any(FinancialTransaction.class),any(BigDecimal.class),any(BigDecimal.class),anyString());
        sell(asset,confirmation(draft,q),"extra-child").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEST_EXTRA_CHILD"));
        assertThat(witnessed).isTrue();assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void saleRequiresAdminCurrentHouseholdAndTheAssignedDueConfirmer() throws Exception {
        long asset=asset("OTHER"),loan=dueLoan(asset);
        String draft=draft("DIRECT","15000.00","0.00",account,account,payoffSelection(loan),false,"2026-01-31");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());var owner=session;
        var reader=joinMember();session=reader;
        preview(asset,draft).andExpect(status().isForbidden());sell(asset,confirmation(draft,q),"reader").andExpect(status().isForbidden());
        session=owner;long assignee=jdbc.queryForObject("select user_id from household_memberships where household_id=? and role='MEMBER'",Long.class,household);
        jdbc.update("update loans set assigned_user_id=? where id=?",assignee,loan);var before=completeSnapshot();
        preview(asset,draft).andExpect(status().isForbidden());sell(asset,confirmation(draft,q),"wrong-assignee").andExpect(status().isForbidden());
        assertThat(completeSnapshot()).isEqualTo(before);
        session=login(register());
        preview(asset,draft).andExpect(status().isNotFound());sell(asset,confirmation(draft,q),"foreign").andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/"+asset+"/sale").session(session)).andExpect(status().isNotFound());
    }
    @Test void unrelatedDuplicateForeignAndMalformedSelectionsNeverWrite() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00"),unrelated=loan("1000.00");var before=completeSnapshot();
        String valid=draft("DIRECT","15000.00","0.00",account,account,payoffSelection(loan),false,"2026-01-01");
        preview(asset,valid.replace("\"loanId\":"+loan,"\"loanId\":"+unrelated)).andExpect(status().isBadRequest());
        preview(asset,valid.replace(payoffSelection(loan),"[{\"loanId\":"+loan+",\"mode\":\"PAYOFF\"},{\"loanId\":"+loan+",\"mode\":\"PAYOFF\"}]")).andExpect(status().isBadRequest());
        preview(asset,valid.replace("15000.00","-1.00")).andExpect(status().isBadRequest());
        preview(asset,valid.replace("15000.00","0.001")).andExpect(status().isBadRequest());
        preview(asset,valid.replace("\"cashAccountId\":"+account,"\"cashAccountId\":null")).andExpect(status().isBadRequest());
        assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void previewChecksDatedCashSoLaterIncomeCannotFundEarlierTopUp() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");fund("0.00");
        long income=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='INCOME'",Long.class,household);
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"INCOME\",\"amount\":\"10000.00\",\"occurredOn\":\"2026-02-01\",\"accountId\":"+account+",\"memberId\":"+member+",\"categoryId\":"+income+"}"))
            .andExpect(status().isCreated());
        String draft=draft("DIRECT","0.00","0.00",account,null,payoffSelection(loan),false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.balances[0].after").value("0.00"))
            .andExpect(jsonPath("$.data.canConfirm").value(false)).andReturn());
        var before=completeSnapshot();sell(asset,confirmation(draft,q),"late-income").andExpect(status().isConflict());assertThat(completeSnapshot()).isEqualTo(before);
    }
    @Test void quoteKeepsOneReadSnapshotAcrossConcurrentLoanMetadataEdit() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        String draft=draft("DIRECT","15000.00","0.00",account,account,payoffSelection(loan),false,"2026-01-01");
        var original=data(preview(asset,draft).andExpect(status().isOk()).andReturn());var once=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            if(!invocation.<Boolean>getArgument(2)&&once.compareAndSet(false,true)){
                var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
                try{pool.submit(()->jdbc.update("update loan_installments set precise_interest_amount=0.000000000001 where loan_id=? and installment_no=2",loan)).get(10,java.util.concurrent.TimeUnit.SECONDS);}
                finally{pool.shutdownNow();}
            }
            return invocation.callRealMethod();
        }).when(plans).pending(eq(household),eq(loan),anyBoolean());
        assertThat(data(preview(asset,draft).andExpect(status().isOk()).andReturn())).isEqualTo(original);
        sell(asset,confirmation(draft,original),"snapshot").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_PLAN_CHANGED"));
    }
    @Test void oldBatchJsonDefaultsToCashAndNewDirectBatchKeepsOriginalResultAfterLaterPayment() throws Exception {
        long asset=asset("OTHER"),loan=dueLoan(asset);
        var ordinary=data(mvc.perform(get("/api/loans/"+loan+"/repayment-preview").session(session).param("additionalPrincipal","1000.00")
            .param("paidOn","2026-01-01")).andExpect(status().isOk()).andReturn());
        String ordinaryBody="{\"additionalPrincipal\":\"1000.00\",\"paidOn\":\"2026-01-01\",\"planToken\":\""+ordinary.path("planToken").asText()+"\",\"idempotencyKey\":\"old-batch\"}";
        mvc.perform(post("/api/loans/"+loan+"/repayment").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ordinaryBody)).andExpect(status().isOk());
        String raw=jdbc.queryForObject("select preview_json from loan_repayment_batches where loan_id=?",String.class,loan);
        var old=(tools.jackson.databind.node.ObjectNode)json.readTree(raw);old.remove("cashImpact");old.remove("settlementAssetId");
        jdbc.update("update loan_repayment_batches set preview_json=? where loan_id=?",old.toString(),loan);
        mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].cashImpact").value(true));
        String selected="[{\"loanId\":"+loan+",\"mode\":\"PARTIAL\",\"additionalPrincipal\":\"1000.00\"}]";
        String draft=draft("DIRECT","5000.00","0.00",account,account,selected,false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());String body=confirmation(draft,q);
        var receipt=data(sell(asset,body,"immutable").andExpect(status().isOk()).andReturn());
        var history=data(mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"100.00\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\"later\"}")).andExpect(status().isOk());
        assertThat(data(sell(asset,body,"immutable").andExpect(status().isOk()).andReturn())).isEqualTo(receipt);
        assertThat(data(mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk()).andReturn())).isEqualTo(history);
    }
    Map<String,List<Map<String,Object>>> completeSnapshot(){
        return snapshot("loans","assets","asset_valuations","loan_installments","loan_prepayments","loan_repayment_batches","loan_repayment_batch_children",
            "financial_transactions","ledger_journals","ledger_entries","ledger_accounts","ledger_sources","accounting_commands","asset_sale_receipts","notifications");
    }
    @Test void legacyDisposalRejectsOutstandingDebtButNoLoanDisposalAndReplayRemainUsable() throws Exception {
        long asset=asset("OTHER");linkedLoan(asset,"10000.00");var before=allSnapshot();
        String request="{\"disposedOn\":\"2026-01-01\",\"proceeds\":\"5000.00\",\"cashAccountId\":"+account+"}";
        mvc.perform(post("/api/assets/"+asset+"/dispose").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_REQUIRED"));
        assertThat(allSnapshot()).isEqualTo(before);
        long noLoan=asset("OTHER");String key=UUID.randomUUID().toString();
        var first=data(mvc.perform(post("/api/assets/"+noLoan+"/dispose").session(session).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
        var settled=completeSnapshot();
        var replay=data(mvc.perform(post("/api/assets/"+noLoan+"/dispose").session(session).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andReturn());
        // Linux clocks expose nanoseconds; timestamp(6) storage rounds to microseconds.
        // Preserve exact business-field equality and permit only that bounded timestamp rounding.
        assertThat(java.time.Duration.between(java.time.Instant.parse(first.path("archivedAt").asText()),
            java.time.Instant.parse(replay.path("archivedAt").asText())).abs().toNanos()).isLessThanOrEqualTo(1000L);
        var originalFields=(tools.jackson.databind.node.ObjectNode)first.deepCopy();
        var replayFields=(tools.jackson.databind.node.ObjectNode)replay.deepCopy();
        originalFields.remove("archivedAt");replayFields.remove("archivedAt");
        assertThat(replayFields).isEqualTo(originalFields);
        assertThat(completeSnapshot()).isEqualTo(settled);
        mvc.perform(get("/api/assets/"+noLoan+"/sale").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void zeroAssetCanArchiveWithClosedOrArchivedZeroDebtReferencesWithoutUnlinkingHistory() throws Exception {
        for(boolean archived:List.of(false,true)){
            long asset=asset("OTHER"),loan=linkedLoan(asset,"1000.00");
            mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1000.00\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isOk());
            if(archived)mvc.perform(delete("/api/loans/"+loan).session(session).with(csrf())).andExpect(status().isNoContent());
            revalue(asset,"0.00");
            mvc.perform(delete("/api/assets/"+asset).session(session).with(csrf())).andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("select linked_asset_id from loans where id=?",Long.class,loan)).isEqualTo(asset);
        }
    }
    @Test void retainingDebtRequiresExplicitConfirmationAndPreservesTheLoanAndLink() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        String draft=draft("VIA_ACCOUNT","5000.00","100.00",account,null,"[]",false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.canConfirm").value(false))
            .andExpect(jsonPath("$.data.retainedLoans[0].remainingPrincipal").value("10000.00")).andReturn());
        var before=allSnapshot();
        sell(asset,confirmation(draft,q),UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_RETAIN_REQUIRED"));
        assertThat(allSnapshot()).isEqualTo(before);
        draft=draft.replace("false","true");q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());
        sell(asset,confirmation(draft,q),UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(1000000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(6490000);
        assertThat(jdbc.queryForObject("select linked_asset_id from loans where id=?",Long.class,loan)).isEqualTo(asset);
        assertThat(jdbc.queryForObject("select status from loans where id=?",String.class,loan)).isEqualTo("ACTIVE");
        assertThat(count("financial_transactions")).isZero();
    }
    @Test void viaAccountCreditsActualProceedsBeforePayoffAndOrdinaryRepaymentFlagsStayCash() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");fund("0.00");
        String draft=draft("VIA_ACCOUNT","15000.00","100.00",account,null,payoffSelection(loan),false,"2026-01-01");
        confirm(asset,draft);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(490000);
        assertThat(jdbc.queryForObject("select count(*) from financial_transactions where household_id=? and asset_settlement_id is null",Long.class,household)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries e join ledger_journals j on j.id=e.journal_id where j.household_id=? and j.source_type='LOAN_PREPAYMENT' and e.account_code like 'CASH:%'",Long.class,household)).isEqualTo(1);
        mvc.perform(get("/api/loans/"+loan+"/prepayments").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].cashImpact").value(true));
    }
    @ParameterizedTest @CsvSource({"VIA_ACCOUNT,REDUCE_PAYMENT","VIA_ACCOUNT,REDUCE_TERM","DIRECT,REDUCE_PAYMENT","DIRECT,REDUCE_TERM","DIRECT,ADJUST_TERM"})
    void partialSettlesDueInterestAndExtraThenRebuildsOnlyFuturePlan(String route,String strategy) throws Exception {
        long asset=asset("OTHER"),loan=dueLoan(asset);fund("0.00");
        jdbc.update("insert into notifications(household_id,user_id,type,title,reference_type,reference_id) select household_id,?,'LOAN_DUE','Due fixture','LOAN_INSTALLMENT',id from loan_installments where loan_id=?",user,loan);
        String selection="[{\"loanId\":"+loan+",\"mode\":\"PARTIAL\",\"additionalPrincipal\":\"3000.00\",\"strategy\":\""+strategy+"\""+(strategy.equals("ADJUST_TERM")?",\"targetPeriods\":1":"")+"}]";
        String draft=draft(route,"5000.00","100.00",account,null,selection,false,"2026-01-31");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalPrincipal").value("4000.00"))
            .andExpect(jsonPath("$.data.totalInterest").value("100.00")).andExpect(jsonPath("$.data.totalRepayment").value("4100.00"))
            .andExpect(jsonPath("$.data.loans[0].remainingPrincipal").value("6000.00")).andReturn());
        sell(asset,confirmation(draft,q),UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(80000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(600000);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(10000);
        assertThat(ledger.balance(household,"ASSET_SALE_CLEARING:"+asset)).isZero();
        assertThat(count("financial_transactions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(principal_amount) from loan_installments where loan_id=? and status='PENDING'",BigDecimal.class,loan)).isEqualByComparingTo("6000.00");
        assertThat(jdbc.queryForObject("select count(*) from notifications where household_id=? and resolved_at is null",Long.class,household)).isZero();
        mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].balanceAfter").value("800.00")).andExpect(jsonPath("$.data[0].cashImpact").value(!route.equals("DIRECT")))
            .andExpect(jsonPath("$.data[0].totalCashAmount").value("4100.00"));
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }
    @Test void selectedRepaymentAccountGetsNoImplicitTransferFromSaleReceiptAccount() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00"),other=cashAccount("0.00");fund("0.00");
        String draft=draft("VIA_ACCOUNT","15000.00","0.00",account,other,payoffSelection(loan),false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.canConfirm").value(false)).andReturn());
        var before=allSnapshot();sell(asset,confirmation(draft,q),UUID.randomUUID().toString()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));assertThat(allSnapshot()).isEqualTo(before);
        preview(asset,draft.replace("VIA_ACCOUNT","DIRECT")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/accounts/"+other).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"openingBalance\":\"10000.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
        confirm(asset,draft);assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(1500000);
        assertThat(ledger.balance(household,"CASH:"+other)).isZero();
    }
    @Test void directShortfallUsesOnlyRealTopUpAndRejectsUnfundedDate() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");fund("9000.00");
        String draft=draft("DIRECT","1000.00","100.00",account,account,payoffSelection(loan),false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.netSettlement").value("-9100.00"))
            .andExpect(jsonPath("$.data.canConfirm").value(false)).andReturn());
        sell(asset,confirmation(draft,q),UUID.randomUUID().toString()).andExpect(status().isConflict());
        fund("10000.00");confirm(asset,draft);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(90000);
        assertThat(ledger.balance(household,"ASSET_SALE_CLEARING:"+asset)).isZero();
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"VIA_ACCOUNT","DIRECT"})
    void multipleLoansUseCoherentProjectedSequentialBalances(String route) throws Exception {
        long asset=asset("OTHER"),first=linkedLoan(asset,"10000.00"),second=linkedLoan(asset,"10000.00");fund("0.00");
        String selected="[{\"loanId\":"+first+",\"mode\":\"PARTIAL\",\"additionalPrincipal\":\"1000.00\"},{\"loanId\":"+second+",\"mode\":\"PARTIAL\",\"additionalPrincipal\":\"1000.00\"}]";
        confirm(asset,draft(route,"5000.00","0.00",account,account,selected,false,"2026-01-01"));
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(300000);
        assertThat(jdbc.queryForList("select balance_after from loan_repayment_batches where household_id=? order by id",BigDecimal.class,household))
            .usingComparatorForType(BigDecimal::compareTo,BigDecimal.class).containsExactly(new BigDecimal(route.equals("DIRECT")?"3000.00":"4000.00"),new BigDecimal("3000.00"));
    }
    @Test void staleCashAssetAndLoanSnapshotsRequireNewReviewAndChangedRetryBodyCannotReplay() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        String draft=draft("DIRECT","15000.00","100.00",account,account,payoffSelection(loan),false,"2026-01-01");
        var q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());fund("60001.00");
        var before=allSnapshot();sell(asset,confirmation(draft,q),"stale-balance").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_PLAN_CHANGED"));assertThat(allSnapshot()).isEqualTo(before);
        q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());revalue(asset,"190000.00");
        sell(asset,confirmation(draft,q),"stale-asset").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_PLAN_CHANGED"));
        q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());
        jdbc.update("update loan_installments set precise_interest_amount=0.000000000001 where loan_id=? and installment_no=2",loan);
        sell(asset,confirmation(draft,q),"stale-plan").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_SALE_PLAN_CHANGED"));
        q=data(preview(asset,draft).andExpect(status().isOk()).andReturn());String request=confirmation(draft,q);sell(asset,request,"stable").andExpect(status().isOk());
        sell(asset,request.replace("15000.00","15000.0"),"stable").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void zeroProceedsWithoutLoansNeedsNoAccountAndStillKeepsAnImmutableReceipt() throws Exception {
        long asset=asset("OTHER");revalue(asset,"0.00");
        confirm(asset,draft("DIRECT","0.00","0.00",null,null,"[]",false,"2026-01-01"));
        assertThat(count("asset_sale_receipts")).isEqualTo(1);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(6000000);
    }
    @Test void directPayoffPostsOnlyNetCashAndReplaysImmutableReceipt() throws Exception {
        long asset=asset("OTHER"),loan=linkedLoan(asset,"10000.00");
        String draft=draft("DIRECT","15000.00","100.00",account,account,
            "[{\"loanId\":"+loan+",\"mode\":\"PAYOFF\"}]",false,"2026-01-01");
        var before=allSnapshot();
        var quote=data(preview(asset,draft).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalRepayment").value("10000.00"))
            .andExpect(jsonPath("$.data.netSettlement").value("4900.00"))
            .andExpect(jsonPath("$.data.bookGain").value("-185100.00"))
            .andExpect(jsonPath("$.data.canConfirm").value(true)).andReturn());
        assertThat(allSnapshot()).isEqualTo(before);
        String request=confirmation(draft,quote),key=UUID.randomUUID().toString();
        var receipt=data(sell(asset,request,key).andExpect(status().isOk()).andReturn());
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(6490000);
        assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();
        assertThat(ledger.balance(household,"ASSET_SALE_CLEARING:"+asset)).isZero();
        assertThat(jdbc.queryForList("select distinct asset_settlement_id from financial_transactions where household_id=?",Long.class,household)).containsExactly(asset);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries e join ledger_journals j on j.id=e.journal_id where j.household_id=? and j.source_type='LOAN_PREPAYMENT' and e.account_code like 'CASH:%'",Long.class,household)).isZero();
        assertThat(jdbc.queryForObject("select status from assets where id=?",String.class,asset)).isEqualTo("ARCHIVED");
        var finished=allSnapshot();
        assertThat(data(sell(asset,request,key).andExpect(status().isOk()).andReturn())).isEqualTo(receipt);
        assertThat(data(mvc.perform(get("/api/assets/"+asset+"/sale").session(session)).andExpect(status().isOk()).andReturn())).isEqualTo(receipt);
        assertThat(allSnapshot()).isEqualTo(finished);
        mvc.perform(get("/api/loans/"+loan+"/prepayments").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].cashImpact").value(false))
            .andExpect(jsonPath("$.data[0].settlementAssetId").value(asset));
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }
    long linkedLoan(long asset,String principal) throws Exception {
        return data(create(body("OPENING").replace("150000.00",principal)
            .replace("\"linkedAssetId\":null","\"linkedAssetId\":"+asset),UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    long dueLoan(long asset) throws Exception {
        String schedule="[{\"dueOn\":\"2026-01-31\",\"principal\":\"1000.00\",\"interest\":\"100.00\"},{\"dueOn\":\"2026-02-28\",\"principal\":\"4500.00\",\"interest\":\"0.00\"},{\"dueOn\":\"2026-03-31\",\"principal\":\"4500.00\",\"interest\":\"0.00\"}]";
        String body=body("OPENING").replace("150000.00","10000.00").replace("\"linkedAssetId\":null","\"linkedAssetId\":"+asset)
            .replace("\"termMonths\":10","\"termMonths\":3").replace("EQUAL_PAYMENT","CUSTOM").replace("\"customSchedule\":null","\"customSchedule\":"+schedule);
        return data(create(body,UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    long cashAccount(String amount) throws Exception {
        return data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Other "+UUID.randomUUID()+"\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}"))
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    void revalue(long asset,String amount) throws Exception {
        mvc.perform(post("/api/assets/"+asset+"/valuations").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"value\":\""+amount+"\",\"valuedOn\":\"2026-01-01\"}")).andExpect(status().isCreated());
    }
    String payoffSelection(long loan){return "[{\"loanId\":"+loan+",\"mode\":\"PAYOFF\"}]";}
    JsonNode confirm(long asset,String draft) throws Exception {
        var quote=data(preview(asset,draft).andExpect(status().isOk()).andExpect(jsonPath("$.data.canConfirm").value(true)).andReturn());
        return data(sell(asset,confirmation(draft,quote),UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
    }
    String draft(String route,String proceeds,String fee,Long cash,Long repayment,String loans,boolean retain,String day){
        return "{\"disposedOn\":\""+day+"\",\"proceeds\":\""+proceeds+"\",\"fee\":\""+fee+"\",\"cashAccountId\":"+cash
            +",\"repaymentAccountId\":"+repayment+",\"route\":\""+route+"\",\"repayments\":"+loans+",\"retainUnselectedLoans\":"+retain+"}";
    }
    ResultActions preview(long asset,String draft) throws Exception {return mvc.perform(post("/api/assets/"+asset+"/sale-preview").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(draft));}
    String confirmation(String draft,JsonNode quote){return "{\"draft\":"+draft+",\"planToken\":\""+quote.path("planToken").asText()+"\"}";}
    ResultActions sell(long asset,String body,String key) throws Exception {return mvc.perform(post("/api/assets/"+asset+"/sale").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
}
