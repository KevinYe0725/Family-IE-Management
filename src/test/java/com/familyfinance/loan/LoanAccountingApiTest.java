package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import java.time.*;
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
class LoanAccountingApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Autowired LedgerReadService ledger; @Autowired Clock clock;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Autowired jakarta.persistence.EntityManager em; @Autowired LoanRepository loanRepository;
 @Autowired LoanInstallmentConfirmationService confirmations;
 MockHttpSession session; long household,member,user,category,account;
 @BeforeEach void setup() throws Exception {
  String email=UUID.randomUUID()+"@loan.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Loan\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Loan test\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);
  household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);
  member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
  category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
  account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
 }
 @Test void zeroCashScheduledPaymentRollsBackEveryBusinessAndLedgerWrite() throws Exception {
  fund("0.00");long loan=create("OPENING");long installment=first(loan);long journals=count("ledger_journals"),commands=count("accounting_commands");
  pay(installment,"empty","2026-01-03").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  assertThat(count("financial_transactions")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(count("accounting_commands")).isEqualTo(commands);
  assertThat(jdbc.queryForObject("select status from loan_installments where id=?",String.class,installment)).isEqualTo("PENDING");
  assertThat(principal(loan)).isEqualTo(200000);assertThat(ledger.balance(household,"CASH:"+account)).isZero();
 }
 @Test void dashboardAndLedgerIncludeTheSameFullRepaymentOnItsActualDate() throws Exception {
  fund("1100.00");long loan=create("OPENING");
  pay(first(loan),"report-payment","2026-01-03").andExpect(status().isOk());
  mvc.perform(get("/api/transactions/summary").session(session).param("month","2026-01"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.expense").value("1100.00"));
  mvc.perform(get("/api/dashboard").session(session).param("month","2026-01"))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.data.summary.income").value("0.00"))
   .andExpect(jsonPath("$.data.summary.expense").value("1100.00"))
   .andExpect(jsonPath("$.data.summary.balance").value("-1100.00"))
   .andExpect(jsonPath("$.data.daily[0].date").value("2026-01-03"))
   .andExpect(jsonPath("$.data.daily[0].expense").value("1100.00"))
   .andExpect(jsonPath("$.data.expenseByCategory[0].amount").value("1100.00"))
   .andExpect(jsonPath("$.data.expenseByMember[0].amount").value("1100.00"));
  mvc.perform(get("/api/budgets/expense-summary").session(session).param("periodMonth","2026-01"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.expense").value("100.00"));
  mvc.perform(get("/api/plugins/annual-stats").session(session).param("year","2026"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[0].expense").value("1100.00"))
   .andExpect(jsonPath("$.data.summary.expense").value("1100.00"));
 }
 @Test void exactFundsSplitPrincipalInterestAndMetadataKeepsAllHistory() throws Exception {
  fund("1100.00");long loan=create("OPENING"),installment=first(loan);
  long tx=data(pay(installment,"exact","2026-01-03").andExpect(status().isOk()).andExpect(jsonPath("$.data.paidOn").value("2026-01-03")).andReturn()).path("confirmedTransactionId").asLong();
  assertThat(ledger.balance(household,"CASH:"+account)).isZero();assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(100000);assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(10000);
  pay(installment,"exact","2026-01-03").andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedTransactionId").value(tx));
  pay(installment,"exact","2026-01-04").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New name\"}")).andExpect(status().isOk());
  assertThat(principal(loan)).isEqualTo(100000);assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan)).isEqualTo(2);
  mvc.perform(patch("/api/transactions/"+tx).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"1.00\"}")).andExpect(status().isConflict());
  mvc.perform(delete("/api/transactions/"+tx).session(session).with(csrf())).andExpect(status().isConflict());
  mvc.perform(get("/api/transactions/"+tx).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.principalAmount").value("1000.00")).andExpect(jsonPath("$.data.interestAmount").value("100.00"));
  assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
 }
 @Test void openingCreatesOnlyLiabilityDisbursementCreatesCashAndReplayCannotChangeContent() throws Exception {
  fund("0.00");long opening=create("OPENING");assertThat(ledger.balance(household,"LOAN:"+opening)).isEqualTo(200000);assertThat(ledger.balance(household,"CASH:"+account)).isZero();
  String b=body("DISBURSEMENT");long loan=data(createCall(b,"disburse").andExpect(status().isCreated()).andReturn()).path("id").asLong();
  createCall(b,"disburse").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(loan));
  createCall(b.replace("Loan","Different"),"disburse").andExpect(status().isConflict());
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(200000);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(200000);assertThat(count("financial_transactions")).isZero();
 }
 @Test void prepaymentShortageRollsBackThenExactFullCloseIsIdempotent() throws Exception {
  fund("0.00");long loan=create("OPENING");long journals=count("ledger_journals"),commands=count("accounting_commands");
  prepay(loan,"2000.00","2026-01-01","prepay").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  assertThat(count("loan_prepayments")).isZero();assertThat(count("financial_transactions")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(count("accounting_commands")).isEqualTo(commands);assertThat(principal(loan)).isEqualTo(200000);
  fund("2000.00");prepay(loan,"2000.00","2026-01-01","prepay").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
  prepay(loan,"2000.00","2026-01-01","prepay").andExpect(status().isOk());
  prepay(loan,"1999.00","2026-01-01","prepay").andExpect(status().isConflict());prepay(loan,"2000.00","2026-01-04","prepay").andExpect(status().isConflict());
  assertThat(ledger.balance(household,"CASH:"+account)).isZero();assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();assertThat(ledger.balance(household,"EXPENSE:"+category)).isZero();
  assertThat(count("loan_prepayments")).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isZero();
 }
 @Test void historicalAllocationsAndFinancialContractCannotBeRewritten() throws Exception {
  fund("5000.00");long loan=createFuture();prepay(loan,"100.00","2026-01-05","first").andExpect(status().isOk());
  long rows=jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan);
  prepay(loan,"100.00","2026-01-04","earlier").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYMENT_CHRONOLOGY"));
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"After prepay\"}")).andExpect(status().isOk());
  assertThat(principal(loan)).isEqualTo(190000);assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan)).isEqualTo(rows);
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"principal\":\"100.00\"}")).andExpect(status().isConflict());
  mvc.perform(delete("/api/loans/"+loan).session(session).with(csrf())).andExpect(status().isConflict());
 }
 @Test void actualDateDefaultsTodayAndCannotPrecedeAccountOrLoanOpening() throws Exception {
  fund("1100.00",today());long loan=create("OPENING");long installment=first(loan);
  pay(installment,"before","2026-01-03").andExpect(status().isConflict());
  mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidOn").value(today()));
  assertThat(ledger.balance(household,"CASH:"+account)).isZero();
 }
 @Test void loanAndDisbursementRequireExplicitInitializationAndValidDates() throws Exception {
  createCall(body("DISBURSEMENT"),"unconfirmed").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
  fund("0.00");createCall(body("OPENING").replace("\"fundingMode\":\"OPENING\",",""),"missing").andExpect(status().isBadRequest());
  createCall(body("OPENING").replace("\"accountingOn\":\"2026-01-01\"", "\"accountingOn\":null"),"missing-date").andExpect(status().isBadRequest());
  createCall(body("OPENING").replace("\"accountingOn\":\"2026-01-01\"","\"accountingOn\":\"9999-01-01\""),"future").andExpect(status().isBadRequest());
 }
 @Test void unpaidContractCorrectionReplacesOpeningAndPreservesIdempotency() throws Exception {
  fund("0.00");long loan=create("DISBURSEMENT");
  String correction="{\"principal\":\"1000.00\",\"termMonths\":1,\"customSchedule\":[{\"dueOn\":\"2026-01-02\",\"principal\":\"1000.00\",\"interest\":\"10.00\"}]}";
  for(int n=0;n<2;n++)mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).header("Idempotency-Key","correct").contentType(MediaType.APPLICATION_JSON).content(correction)).andExpect(status().isOk());
  assertThat(principal(loan)).isEqualTo(100000);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(100000);assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(100000);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan)).isEqualTo(1);
 }
 @Test void futurePaymentAccountCanChangeWithoutResettingPaidHistory() throws Exception {
  fund("1100.00");long loan=create("OPENING");pay(first(loan),"pay","2026-01-03").andExpect(status().isOk());
  long next=data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"next\",\"type\":\"BANK\",\"currency\":\"CNY\",\"openingBalance\":\"1000.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paymentAccountId\":"+next+"}")).andExpect(status().isOk());
  mvc.perform(delete("/api/accounts/"+account).session(session).with(csrf())).andExpect(status().isNoContent());
  prepay(loan,"1000.00","2026-01-04","close").andExpect(status().isOk());assertThat(ledger.balance(household,"CASH:"+next)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(1);
 }
 @Test void contractCorrectionCannotRemoveSpentDisbursementAndRollsBackSchedule() throws Exception {
  fund("0.00");long loan=create("DISBURSEMENT");
  mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"EXPENSE\",\"amount\":\"2000.00\",\"occurredOn\":\"2026-01-02\",\"accountId\":"+account+",\"memberId\":"+member+",\"categoryId\":"+category+"}")).andExpect(status().isCreated());
  long first=first(loan),journals=count("ledger_journals");
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"principal\":\"1000.00\",\"termMonths\":1,\"customSchedule\":[{\"dueOn\":\"2026-01-02\",\"principal\":\"1000.00\",\"interest\":\"10.00\"}]}"))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  assertThat(principal(loan)).isEqualTo(200000);assertThat(first(loan)).isEqualTo(first);assertThat(count("ledger_journals")).isEqualTo(journals);
  mvc.perform(delete("/api/accounts/"+account).session(session).with(csrf()))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
 }
 @Test void futureDueOrderLoanOpeningAndOverpaymentBoundariesHaveNoWrites() throws Exception {
  fund("5000.00");long loan=create("OPENING");long second=jdbc.queryForObject("select max(id) from loan_installments where loan_id=?",Long.class,loan);
  pay(second,"order","2026-02-03").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYMENT_ORDER"));
  prepay(loan,"2000.01","2026-01-03","over").andExpect(status().isBadRequest());
  prepay(loan,"100.00","2025-12-31","before-loan").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYMENT_BEFORE_OPENING"));
  prepay(loan,"100.00","9999-01-01","future").andExpect(status().isBadRequest());
  long futureLoan=data(createCall(body("OPENING").replace("2026-01-02","9999-01-02").replace("2026-02-02","9999-02-02"),"future-loan").andExpect(status().isCreated()).andReturn()).path("id").asLong();
  pay(first(futureLoan),"not-due",today()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSTALLMENT_NOT_DUE"));
  assertThat(count("financial_transactions")).isZero();assertThat(principal(loan)).isEqualTo(200000);
 }
 @Test void readOnlyHouseholdRoleCannotCreateCorrectConfirmOrPrepay() throws Exception {
  fund("5000.00");long loan=create("OPENING"),installment=first(loan);
  String token=data(mvc.perform(post("/api/family/invites").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}")).andExpect(status().isCreated()).andReturn()).path("token").asText();
  String email=UUID.randomUUID()+"@reader.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Reader\",\"password\":\"loan-test-password\",\"mode\":\"JOIN\",\"inviteToken\":\""+token+"\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk());
  createCall(body("OPENING"),"viewer-create").andExpect(status().isForbidden());
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"viewer\"}")).andExpect(status().isForbidden());
  pay(installment,"viewer-pay","2026-01-03").andExpect(status().isForbidden());prepay(loan,"100.00","2026-01-03","viewer-prepay").andExpect(status().isForbidden());
  assertThat(principal(loan)).isEqualTo(200000);assertThat(count("financial_transactions")).isZero();
 }
 @Test void earlierSnapshotCannotHideCommittedPaymentWhenConfirmingNextInstallment() throws Exception {
  fund("2150.00");long loan=create("OPENING"),first=first(loan),second=jdbc.queryForObject("select max(id) from loan_installments where loan_id=?",Long.class,loan);
  withEarlierSnapshot(loan,()->pay(first,"first-current","2026-01-03").andExpect(status().isOk()),()->pay(second,"second-current","2026-02-03").andExpect(status().isOk()));
  assertThat(principal(loan)).isZero();assertThat(ledger.balance(household,"CASH:"+account)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(2);
 }
 @Test void earlierSnapshotCannotLoseRegeneratedScheduleOnSecondPrepayment() throws Exception {
  fund("1000.00");long loan=createFuture();
  withEarlierSnapshot(loan,()->prepay(loan,"100.00","2026-01-03","prepay-current1").andExpect(status().isOk()),()->prepay(loan,"100.00","2026-01-04","prepay-current2").andExpect(status().isOk()));
  assertThat(principal(loan)).isEqualTo(180000);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(180000);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isEqualTo(2);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='CANCELLED'",Long.class,loan)).isEqualTo(4);
 }
 @Test void existingPrincipalDriftIsRejectedWithoutAnyAdditionalMoneyWrites() throws Exception {
  fund("5000.00");long loan=create("OPENING"),journals=count("ledger_journals");
  jdbc.update("update loans set current_principal_amount=1900.00 where id=?",loan);
  pay(first(loan),"drift-pay","2026-01-03").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_BALANCE_MISMATCH"));
  prepay(loan,"100.00","2026-01-03","drift-prepay").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_BALANCE_MISMATCH"));
  assertThat(principal(loan)).isEqualTo(190000);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(200000);assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(count("financial_transactions")).isZero();
  jdbc.update("update loans set current_principal_amount=0 where id=?",loan);
  mvc.perform(delete("/api/loans/"+loan).session(session).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_BALANCE_MISMATCH"));
 }
 @Test void oldLoanRowsStayUninitializedAndCannotBePaidOrSilentlyRebooked() throws Exception {
  fund("5000.00");var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  long id=tx.execute(ignored->{
   var loan=new Loan(em.find(com.familyfinance.household.Household.class,household),"Legacy",LoanType.OTHER,null,em.find(com.familyfinance.household.FamilyMember.class,member),em.find(com.familyfinance.household.AppUser.class,user),em.find(com.familyfinance.ledger.FinancialAccount.class,account),em.find(com.familyfinance.category.Category.class,category),200000,new java.math.BigDecimal("0.1"),1,RepaymentMethod.EQUAL_PRINCIPAL,LocalDate.of(2025,1,1),em.find(com.familyfinance.household.AppUser.class,user));
   loan.replaceSchedule(java.util.List.of(new InstallmentDraft(1,LocalDate.of(2026,1,2),200000,10000,0)));return loanRepository.saveAndFlush(loan).getId();
  });
  long journals=count("ledger_journals");
  mvc.perform(get("/api/loans/"+id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.accountingInitialized").value(false));
  pay(first(id),"legacy-pay","2026-01-03").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
  prepay(id,"100.00","2026-01-03","legacy-prepay").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
  assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(count("financial_transactions")).isZero();assertThat(principal(id)).isEqualTo(200000);
 }
 @Test void omittedDateReplayRetainsFirstActualDateEvenIfDefaultDayChanges() throws Exception {
  fund("1100.00");long loan=create("OPENING"),installment=first(loan);
  long result=data(mvc.perform(post("/api/loan-installments/"+installment+"/confirm").session(session).with(csrf()).header("Idempotency-Key","no-date")).andExpect(status().isOk()).andReturn()).path("confirmedTransactionId").asLong();
  var auth=((org.springframework.security.core.context.SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication();
  var replay=confirmations.confirm(auth,installment,new LoanPaymentRequest(null),LocalDate.parse(today()).plusDays(1),"no-date");
  assertThat(replay.confirmedTransactionId()).isEqualTo(result);assertThat(replay.paidOn()).isEqualTo(LocalDate.parse(today()));assertThat(principal(loan)).isEqualTo(100000);
 }
 @Test void earlierSnapshotReplaysNewlyCommittedPaymentTransaction() throws Exception {
  fund("1100.00");long loan=create("OPENING"),installment=first(loan);
  withEarlierSnapshot(loan,()->pay(installment,"same-current","2026-01-03").andExpect(status().isOk()),()->pay(installment,"same-current","2026-01-03").andExpect(status().isOk()).andExpect(jsonPath("$.data.paidOn").value("2026-01-03")));
  assertThat(count("financial_transactions")).isEqualTo(1);assertThat(principal(loan)).isEqualTo(100000);assertThat(ledger.balance(household,"CASH:"+account)).isZero();
 }
 @Test void earlierSnapshotReplaysNewlyCreatedLoanAndPrepayment() throws Exception {
  fund("1000.00");String request=body("OPENING").replace("2026-01-02","2026-01-31");
  withEarlierSnapshot(0,()->createCall(request,"create-current").andExpect(status().isCreated()),()->createCall(request,"create-current").andExpect(status().isCreated()).andExpect(jsonPath("$.data.currentPrincipal").value("2000.00")));
  long loan=jdbc.queryForObject("select id from loans where household_id=?",Long.class,household);
  withEarlierSnapshot(loan,()->prepay(loan,"100.00","2026-01-03","prepay-replay-current").andExpect(status().isOk()),()->prepay(loan,"100.00","2026-01-03","prepay-replay-current").andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingPrincipal").value("1900.00")));
  assertThat(count("loans")).isEqualTo(1);assertThat(count("loan_prepayments")).isEqualTo(1);assertThat(count("financial_transactions")).isEqualTo(1);
 }
 private long createFuture()throws Exception{return data(createCall(body("OPENING").replace("2026-01-02","2026-01-31"),"future-plan").andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private void withEarlierSnapshot(long loan,Checked outside,Checked inside)throws Exception {
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  boolean mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
  // H2 RR aborts a locking read of a changed row; MySQL supplies the required current read.
  if(mysql)tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  try{tx.executeWithoutResult(ignored->{
   jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,loan);
   try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();}catch(Exception e){throw new RuntimeException(e);}
  });}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
 }
 @FunctionalInterface private interface Checked {void run()throws Exception;}
 private String today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString();}
 private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
 private long principal(long loan){return jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan);}
 private long first(long loan){return jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);}
 private void fund(String amount)throws Exception{fund(amount,"2026-01-01");}
 private void fund(String amount,String date)throws Exception{mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\""+date+"\"}")).andExpect(status().isOk());}
 private long create(String mode)throws Exception{return data(createCall(body(mode),UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private String body(String mode){return "{\"name\":\"Loan\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"2000.00\",\"annualRate\":0.1,\"termMonths\":2,\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"2025-01-01\",\"fundingMode\":\""+mode+"\",\"accountingOn\":\"2026-01-01\""+(mode.equals("DISBURSEMENT")?",\"disbursementAccountId\":"+account:"")+",\"customSchedule\":[{\"dueOn\":\"2026-01-02\",\"principal\":\"1000.00\",\"interest\":\"100.00\"},{\"dueOn\":\"2026-02-02\",\"principal\":\"1000.00\",\"interest\":\"50.00\"}]}";}
 private ResultActions createCall(String b,String key)throws Exception{return mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(b));}
 private ResultActions pay(long id,String key,String date)throws Exception{return mvc.perform(post("/api/loan-installments/"+id+"/confirm").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\""+date+"\"}"));}
 private ResultActions prepay(long id,String amount,String date,String key)throws Exception{return mvc.perform(post("/api/loans/"+id+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\""+amount+"\",\"paidOn\":\""+date+"\",\"idempotencyKey\":\""+key+"\"}"));}
 private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
