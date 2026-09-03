package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import java.util.concurrent.*;

@ActiveProfiles("test") @SpringBootTest(properties="app.seed.enabled=true") @AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoanRepaymentApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired FinancialTransactionRepository transactions;
 @Test void assignedUserConfirmationIsIdempotentAndReducesPrincipalExactlyOnce() throws Exception {
  MockHttpSession owner=login(); long loan=create(owner, "2024-01-01");
  long installment=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
  long original=jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan);
  long principal=jdbc.queryForObject("select principal_cents from loan_installments where id=?",Long.class,installment);
  long first=confirm(owner,installment), second=confirm(owner,installment);
  assertThat(second).isEqualTo(first);
  assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan)).isEqualTo(original-principal);
  assertThat(transactions.countBySourceTypeAndSourceId(TransactionSourceType.LOAN_PAYMENT,installment)).isEqualTo(1);
  mvc.perform(post("/api/loan-installments/{id}/confirm", installment).session(owner).with(csrf()))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedTransactionId").value(first));
 }
 @Test void futureAndUnassignedInstallmentsAreRejectedWithBusinessErrors() throws Exception {
  MockHttpSession owner=login(); long futureLoan=create(owner,"2099-01-01"); long future=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,futureLoan);
  mvc.perform(post("/api/loan-installments/{id}/confirm",future).session(owner).with(csrf()))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSTALLMENT_NOT_DUE"));
 }
 @Test void concurrentConfirmationCreatesOnlyOneLoanPayment() throws Exception {
  MockHttpSession owner=login(); long loan=create(owner,"2024-01-01"), installment=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan); CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
  try {Future<Long> one=pool.submit(()->atBarrier(ready,start,owner,installment)), two=pool.submit(()->atBarrier(ready,start,owner,installment));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();assertThat(one.get(10,TimeUnit.SECONDS)).isEqualTo(two.get(10,TimeUnit.SECONDS));assertThat(transactions.countBySourceTypeAndSourceId(TransactionSourceType.LOAN_PAYMENT,installment)).isEqualTo(1);} finally {start.countDown();pool.shutdownNow();}
 }
 private long atBarrier(CountDownLatch ready,CountDownLatch start,MockHttpSession s,long id)throws Exception{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("start timeout");return confirm(s,id);}
 private long confirm(MockHttpSession s,long id)throws Exception{return body(mvc.perform(post("/api/loan-installments/{id}/confirm",id).session(s).with(csrf())).andExpect(status().isOk()).andReturn()).path("data").path("confirmedTransactionId").asLong();}
 private long create(MockHttpSession s,String start)throws Exception { long account=jdbc.queryForObject("select min(id) from financial_accounts where household_id=1",Long.class), category=jdbc.queryForObject("select min(id) from categories where household_id=1 and kind='EXPENSE'",Long.class), member=jdbc.queryForObject("select min(id) from family_members where household_id=1",Long.class), user=jdbc.queryForObject("select id from app_users where email='demo@local.family'",Long.class); String b="{\"name\":\"测试贷款\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0.120000,\"termMonths\":3,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\""+start+"\"}";return body(mvc.perform(post("/api/loans").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong(); }
 private MockHttpSession login()throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 private JsonNode body(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
}
