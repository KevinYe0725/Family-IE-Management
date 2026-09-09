package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.accounting.LedgerReportingService;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ResourceConflictException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanPurchasedAssetApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReadService ledger; @Autowired LedgerReportingService reporting;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AccountingRequests requests;
    MockHttpSession session; long household, account, category;

    @BeforeEach void setup() throws Exception {
        String email=UUID.randomUUID()+"@purchase.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Buyer\",\"password\":\"purchase-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Purchase test\"}")).andExpect(status().isCreated());
        session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","purchase-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        household=jdbc.queryForObject("select household_id from app_users where email=?",Long.class,email);
        account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
        category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
    }

    @ParameterizedTest @CsvSource({"MORTGAGE,PROPERTY,房产1,true", "CAR,VEHICLE,车辆1,true", "OTHER,OTHER,其他资产1,false"})
    void pairedPurchaseCreatesOneJournalAndNoCashOrFakeMetadata(String loanType,String assetType,String name,boolean pending) throws Exception {
        long before=count("ledger_journals");
        long borrower=jdbc.queryForObject("select min(id) from family_members where household_id=?",Long.class,household);
        String request=body(loanType).replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"memberId\":"+borrower);
        JsonNode loan=data(create(request,"purchase").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong(), asset=loan.path("purchasedAssetId").asLong();
        assertThat(asset).isPositive(); assertThat(loan.path("linkedAssetId").asLong()).isEqualTo(asset);
        mvc.perform(get("/api/assets/"+asset).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value(name)).andExpect(jsonPath("$.data.type").value(assetType))
            .andExpect(jsonPath("$.data.purchaseValue").value("1000.00")).andExpect(jsonPath("$.data.currentValue").value("1000.00"))
            .andExpect(jsonPath("$.data.initialValue").value("1000.00")).andExpect(jsonPath("$.data.acquiredOn").value("2026-01-01"))
            .andExpect(jsonPath("$.data.ownerMemberId").isEmpty())
            .andExpect(jsonPath("$.data.detailsPending").value(pending)).andExpect(jsonPath("$.data.property").isEmpty()).andExpect(jsonPath("$.data.vehicle").isEmpty())
            .andExpect(jsonPath("$.data.acquisitionSourceType").value("LOAN_FINANCED_PURCHASE")).andExpect(jsonPath("$.data.acquisitionSourceId").value(id));
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(100000);
        assertThat(count("ledger_journals")).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type='ASSET_ACQUISITION'",Long.class,household)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_valuations where household_id=? and asset_id=?",Long.class,household,asset)).isEqualTo(1);
        var cashFlow=reporting.cashFlow(household,LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));
        assertThat(cashFlow.borrowed()).isEqualTo(100000);assertThat(cashFlow.cashIn()).isZero();assertThat(cashFlow.cashOut()).isZero();
        create(request,"purchase").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.purchasedAssetId").value(asset));
        create(request.replace("购买贷款","其他名称"),"purchase").andExpect(status().isConflict());
        assertThat(count("assets")).isEqualTo(1); assertThat(count("loans")).isEqualTo(1); assertThat(count("ledger_journals")).isEqualTo(before+1);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void validMetadataCompletionDoesNotRebookAndManualPropertyStillRequiresDetails() throws Exception {
        var loan=data(create(body("MORTGAGE"),"metadata").andExpect(status().isCreated()).andReturn());long asset=loan.path("purchasedAssetId").asLong();long journals=count("ledger_journals");
        patchAsset(asset,"{\"property\":{\"address\":\"真实地址\",\"areaSqm\":\"0\",\"usageType\":\"自住\"}}").andExpect(status().isUnprocessableEntity());
        patchAsset(asset,"{\"name\":\"我的家\",\"property\":{\"address\":\"真实地址\",\"areaSqm\":\"80.50\",\"usageType\":\"自住\"}}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.detailsPending").value(false)).andExpect(jsonPath("$.data.property.areaSqm").value(80.50));
        assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
        mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"手工房产\",\"type\":\"PROPERTY\",\"currentValue\":\"1000.00\",\"accountingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"伪造购买物\",\"type\":\"OTHER\",\"currentValue\":\"1000.00\",\"accountingMode\":\"FINANCED_PURCHASE\",\"accountingOn\":\"2026-01-01\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.fields.accountingMode").exists());
        assertThat(count("assets")).isEqualTo(1);assertThat(count("ledger_journals")).isEqualTo(journals);
    }

    @Test void immutablePairRejectsFinancialAndLinkChangesButAllowsRateTermWithoutReposting() throws Exception {
        var loan=data(create(body("OTHER"),"immutable").andExpect(status().isCreated()).andReturn());long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();long journals=count("ledger_journals");
        for(String patch:new String[]{"{\"principal\":\"900.00\"}","{\"accountingOn\":\"2026-01-02\"}","{\"startOn\":\"2026-01-02\"}","{\"disbursementAccountId\":"+account+"}","{\"linkedAssetId\":999999}"})
            patchLoan(id,patch).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PURCHASE_IMMUTABLE"));
        patchLoan(id,"{\"annualRate\":0.05,\"termMonths\":3}").andExpect(status().isOk()).andExpect(jsonPath("$.data.termMonths").value(3)).andExpect(jsonPath("$.data.purchasedAssetId").value(asset));
        assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
    }

    @Test void validationAndReplayKeyConflictRejectBeforePairedInserts() throws Exception {
        long journals=count("ledger_journals");
        for(String b:new String[]{body("CAR").replace("true","false"),body("CAR").replace("FINANCED_PURCHASE","OPENING"),body("CAR").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"linkedAssetId\":999999"),body("CAR").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"disbursementAccountId\":"+account),body("CAR").replace("2026-01-01","9999-01-01")})
            create(b,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        // AccountingRequests.replay rejects this pre-existing journal key before either paired insert.
        jdbc.update("insert into ledger_journals(household_id,source_type,source_id,revision,request_key,request_digest,operation,effective_on,actor_id,recorded_at) values (?,'TEST',1,1,'posting-conflict','different-digest','POST','2026-01-01',(select min(id) from app_users where household_id=?),CURRENT_TIMESTAMP)",household,household);
        create(body("CAR"),"posting-conflict").andExpect(status().isConflict());
        assertThat(count("loans")).isZero();assertThat(count("assets")).isZero();assertThat(count("asset_valuations")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals+1);
    }

    @Test void failureAfterRealOriginationAndReceiptRollsBackTheInsertedPairAndEveryPosting() throws Exception {
        var before=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(String table:java.util.List.of("loans","assets","loan_installments","asset_valuations","ledger_journals","ledger_entries","ledger_accounts","ledger_sources","accounting_commands","financial_transactions"))
            before.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));
        long[] observedIds=new long[3];
        doAnswer(invocation->{
            invocation.callRealMethod(); // Keep the genuine receipt INSERT as well as the preceding origination.
            long loan=invocation.getArgument(3);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from loans where household_id=? and id=?",Long.class,household,loan)).isEqualTo(1);
            long asset=jdbc.queryForObject("select purchased_asset_id from loans where household_id=? and id=?",Long.class,household,loan);
            assertThat(jdbc.queryForObject("select count(*) from assets where household_id=? and id=? and purchase_loan_id=?",Long.class,household,asset,loan)).isEqualTo(1);
            long journal=jdbc.queryForObject("select id from ledger_journals where household_id=? and source_type='LOAN_FINANCED_PURCHASE' and source_id=?",Long.class,household,loan);
            assertThat(jdbc.queryForObject("select count(*) from ledger_entries where household_id=? and journal_id=?",Long.class,household,journal)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from asset_valuations where household_id=? and asset_id=?",Long.class,household,asset)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from accounting_commands where household_id=? and request_key='after-real-posting' and source_id=?",Long.class,household,loan)).isEqualTo(1);
            assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
            assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(100000);
            observedIds[0]=loan;observedIds[1]=asset;observedIds[2]=journal;
            throw new ResourceConflictException("TEST_POST_ORIGINATION_FAILURE","Test-only failure after real posting");
        }).when(requests).record(eq(household),eq("after-real-posting"),anyString(),anyLong());
        create(body("CAR"),"after-real-posting").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEST_POST_ORIGINATION_FAILURE"));
        for(long id:observedIds)assertThat(id).isPositive();
        for(var entry:before.entrySet())assertThat(jdbc.queryForList("select * from "+entry.getKey()+" where household_id=? order by 1,2,3",household)).as(entry.getKey()).isEqualTo(entry.getValue());
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void generatedNameSkipsExistingHouseholdName() throws Exception {
        var first=data(create(body("CAR"),"first").andExpect(status().isCreated()).andReturn());
        patchAsset(first.path("purchasedAssetId").asLong(),"{\"name\":\"车辆2\"}").andExpect(status().isOk());
        var second=data(create(body("CAR"),"second").andExpect(status().isCreated()).andReturn());
        var third=data(create(body("CAR"),"third").andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/assets/"+second.path("purchasedAssetId").asLong()).session(session)).andExpect(jsonPath("$.data.name").value("车辆1"));
        mvc.perform(get("/api/assets/"+third.path("purchasedAssetId").asLong()).session(session)).andExpect(jsonPath("$.data.name").value("车辆3"));
    }
    @Test void earlierSnapshotCannotDuplicateNameOrLosePairedCreateReplay() throws Exception {
        withEarlierSnapshot(()->create(body("CAR"),"current-first").andExpect(status().isCreated()),()->{
            create(body("CAR"),"current-first").andExpect(status().isCreated()).andExpect(jsonPath("$.data.purchasedAssetId").isNumber());
            var second=data(create(body("CAR"),"current-second").andExpect(status().isCreated()).andReturn());
            assertThat(jdbc.queryForObject("select name from assets where id=? for update",String.class,second.path("purchasedAssetId").asLong())).isEqualTo("车辆2");
        });
        assertThat(count("assets")).isEqualTo(2);assertThat(count("loans")).isEqualTo(2);
    }
    @Test void earlierSnapshotMetadataCompletionReadsCurrentSubtype() throws Exception {
        var loan=data(create(body("CAR"),"current-metadata").andExpect(status().isCreated()).andReturn());long asset=loan.path("purchasedAssetId").asLong();
        long journals=count("ledger_journals");
        withEarlierSnapshot(()->patchAsset(asset,"{\"vehicle\":{\"brandModel\":\"真实车型\",\"plateHint\":\"真实车牌\",\"purchaseYear\":2026}}").andExpect(status().isOk()),()->{
            patchAsset(asset,"{\"name\":\"我的车\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.brandModel").value("真实车型"))
                .andExpect(jsonPath("$.data.vehicle.plateHint").value("真实车牌")).andExpect(jsonPath("$.data.vehicle.purchaseYear").value(2026));
            patchAsset(asset,"{\"vehicle\":{\"brandModel\":\"更正车型\",\"plateHint\":\"真实车牌\",\"purchaseYear\":2026}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicle.brandModel").value("更正车型")).andExpect(jsonPath("$.data.detailsPending").value(false));
        });
        assertThat(jdbc.queryForObject("select count(*) from vehicle_assets where asset_id=?",Long.class,asset)).isEqualTo(1);
        assertThat(count("ledger_journals")).isEqualTo(journals);
    }
    @Test void principalMustEqualPurchaseValueAndDownPaymentIsRejectedBeforeAnyWrites() throws Exception {
        long loans=count("loans"),assets=count("assets"),valuations=count("asset_valuations"),journals=count("ledger_journals");
        String spec="\"createPurchasedAsset\":true,\"purchasedAsset\":{\"name\":\"车位\",\"purchaseValue\":\"%s\"}";
        for(String bad:new String[]{"1000.01","999.99","abc","-1.00"})
            create(body("OTHER").replace("\"createPurchasedAsset\":true",spec.formatted(bad)),UUID.randomUUID().toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.purchaseValue").exists());
        // 即使 purchaseValue 与本金一致，携带首付资金账户也被拒绝（全额贷款，不存在差额）。
        create(body("OTHER").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"downPaymentAccountId\":"+account),UUID.randomUUID().toString())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.downPaymentAccountId").exists());
        create(body("OTHER").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"downPaymentAccountId\":"+account+",\"purchasedAsset\":{\"purchaseValue\":\"1500.00\"}"),UUID.randomUUID().toString())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.downPaymentAccountId").exists());
        assertThat(count("loans")).isEqualTo(loans);assertThat(count("assets")).isEqualTo(assets);assertThat(count("asset_valuations")).isEqualTo(valuations);assertThat(count("ledger_journals")).isEqualTo(journals);
        // 与本金一致的 spec 正常通过，资产按贷款全额登记且现金分文不动。
        var loan=data(create(body("OTHER").replace("\"createPurchasedAsset\":true",spec.formatted("1000.00")),"equal-value").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();
        mvc.perform(get("/api/assets/"+asset).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("车位")).andExpect(jsonPath("$.data.purchaseValue").value("1000.00"))
            .andExpect(jsonPath("$.data.currentValue").value("1000.00"));
        assertThat(count("ledger_journals")).isEqualTo(journals+1);
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(100000);
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void assetSideFullFinancedCarThenSalaryAndFirstInstallmentKeepEveryBoardConsistent() throws Exception {
        long incomeCategory=incomeCategory();
        // 资产侧“贷款购买”提交体：principal == 购入价值 == spec.purchaseValue，无首付账户。
        var loan=data(create("{\"name\":\"我的新能源车\",\"type\":\"CAR\",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"2000.00\",\"annualRate\":0.06,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"accountingOn\":\"2026-01-01\",\"fundingMode\":\"FINANCED_PURCHASE\",\"createPurchasedAsset\":true,\"purchasedAsset\":{\"name\":\"我的新能源车\",\"purchaseValue\":\"2000.00\"}}","car-full").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(200000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(200000);
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(count("financial_transactions")).isZero();
        // 工资入账后现金增加；资产购买与贷款期初不出现在收支列表。
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"INCOME\",\"amount\":\"5000.00\",\"occurredOn\":\"2026-01-02\",\"accountId\":"+account+",\"memberId\":"+jdbc.queryForObject("select min(id) from family_members where household_id=?",Long.class,household)+",\"categoryId\":"+incomeCategory+"}")).andExpect(status().isCreated());
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(500000);
        assertThat(count("financial_transactions")).isEqualTo(1);
        var list=mvc.perform(get("/api/transactions").session(session)).andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("工资").doesNotContain("新能源车");
        // 首期还款：金额按计划读取，付款后现金、本金与利息科目一致。
        JsonNode schedule=json.readTree(mvc.perform(get("/api/loans/"+id+"/schedule").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        JsonNode firstRow=schedule.get(0);long installment=firstRow.path("id").asLong();String due=firstRow.path("dueOn").asString();
        long principalCents=com.familyfinance.shared.Money.parseCents(firstRow.path("principal").asString());
        long interestCents=com.familyfinance.shared.Money.parseCents(firstRow.path("interest").asString());
        mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf()).header("Idempotency-Key","car-first-pay").contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\""+due+"\"}")).andExpect(status().isOk());
        assertThat(count("financial_transactions")).isEqualTo(2);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(500000-principalCents-interestCents);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(200000-principalCents);
        assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(interestCents);
        assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,id)).isEqualTo(200000-principalCents);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
        var payments=mvc.perform(get("/api/transactions").session(session)).andExpect(status().isOk()).andReturn();
        assertThat(payments.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("工资").contains("贷款还款").doesNotContain("新能源车");
    }

    @Test void disbursedAutoLoanPaysCashCarThenIncomeAndFirstInstallmentStayConsistent() throws Exception {
        long incomeCategory=incomeCategory();
        long assignee=jdbc.queryForObject("select min(id) from app_users where household_id=?",Long.class,household);
        // 银行放款到账（贷款现金入账）——现实中先用贷款资金再现金买车。
        var loan=data(create("{\"name\":\"购车分期\",\"type\":\"CAR\",\"assignedUserId\":"+assignee+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"3000.00\",\"annualRate\":0.06,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"fundingMode\":\"DISBURSEMENT\",\"accountingOn\":\"2026-01-01\",\"disbursementAccountId\":"+account+"}","disburse-car").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong();
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(300000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(300000);
        assertThat(count("financial_transactions")).isZero();
        // 现金购买二手车：资产记账、现金减少，收支列表仍为空（真实购买不属于收支流水）。
        long journals=count("ledger_journals");
        var asset=data(mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"二手卡罗拉\",\"type\":\"VEHICLE\",\"accountingMode\":\"PURCHASE\",\"accountingOn\":\"2026-01-02\",\"acquiredOn\":\"2026-01-02\",\"purchaseValue\":\"2000.00\",\"currentValue\":\"2000.00\",\"fundingAccountId\":"+account+",\"vehicle\":{\"brandModel\":\"卡罗拉 2019\"}}")).andExpect(status().isCreated()).andReturn());
        long assetId=asset.path("id").asLong();
        assertThat(count("ledger_journals")).isEqualTo(journals+1);
        assertThat(ledger.balance(household,"ASSET:"+assetId)).isEqualTo(200000);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(100000);
        assertThat(count("financial_transactions")).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
        // 工资入账后再还首期，现金与科目余额闭环。
        mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"INCOME\",\"amount\":\"3000.00\",\"occurredOn\":\"2026-01-05\",\"accountId\":"+account+",\"memberId\":"+jdbc.queryForObject("select min(id) from family_members where household_id=?",Long.class,household)+",\"categoryId\":"+incomeCategory+"}")).andExpect(status().isCreated());
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(400000);
        JsonNode schedule=json.readTree(mvc.perform(get("/api/loans/"+id+"/schedule").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        JsonNode firstRow=schedule.get(0);long installment=firstRow.path("id").asLong();String due=firstRow.path("dueOn").asString();
        long principalCents=com.familyfinance.shared.Money.parseCents(firstRow.path("principal").asString());
        long interestCents=com.familyfinance.shared.Money.parseCents(firstRow.path("interest").asString());
        mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf()).header("Idempotency-Key","car-loan-first-pay").contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\""+due+"\"}")).andExpect(status().isOk());
        assertThat(count("financial_transactions")).isEqualTo(2);
        assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(400000-principalCents-interestCents);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(300000-principalCents);
        assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,id)).isEqualTo(300000-principalCents);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
        var list=mvc.perform(get("/api/transactions").session(session)).andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("工资").contains("贷款还款").doesNotContain("二手卡罗拉");
    }
    private long incomeCategory()throws Exception{
        Long id=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='INCOME'",Long.class,household);
        if(id!=null)return id;
        return data(mvc.perform(post("/api/categories").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"INCOME\",\"name\":\"工资\",\"color\":\"#00B42A\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }
    private void withEarlierSnapshot(Checked outside,Checked inside)throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        boolean mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
        if(mysql)tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try{tx.executeWithoutResult(ignored->{
            jdbc.queryForObject("select count(*) from assets where household_id=?",Long.class,household);
            jdbc.queryForObject("select count(*) from vehicle_assets where household_id=?",Long.class,household);
            try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();}catch(Exception e){throw new RuntimeException(e);}
        });}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private ResultActions patchAsset(long id,String body)throws Exception{return mvc.perform(patch("/api/assets/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions patchLoan(long id,String body)throws Exception{return mvc.perform(patch("/api/loans/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions create(String body,String key)throws Exception{return mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
    private String body(String type){return "{\"name\":\"购买贷款\",\"type\":\""+type+"\",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0.06,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"accountingOn\":\"2026-01-01\",\"fundingMode\":\"FINANCED_PURCHASE\",\"createPurchasedAsset\":true}";}
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
    private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
