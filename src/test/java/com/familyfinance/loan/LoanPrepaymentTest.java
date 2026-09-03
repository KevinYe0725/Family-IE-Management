package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@ActiveProfiles("test") @SpringBootTest(properties="app.seed.enabled=true") @AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoanPrepaymentTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Test void partialAndFullPrepaymentsAreIdempotentPreservePaidHistoryAndCloseExactly() throws Exception {
  MockHttpSession owner=login(); long loan=create(owner); long first=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan); confirm(owner,first);
  long before=jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan);
  long partial=prepay(owner,loan,"100.00","partial-1");
  assertThat(prepay(owner,loan,"100.00","partial-1")).isEqualTo(partial);
  assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan)).isEqualTo(before-10_000L);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(1L);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isPositive();
  long remaining=jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan);
  prepay(owner,loan,String.format(java.util.Locale.ROOT,"%.2f",remaining/100.0),"full-1");
  assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan)).isZero();
  assertThat(jdbc.queryForObject("select status from loans where id=?",String.class,loan)).isEqualTo("CLOSED");
 }
 private long confirm(MockHttpSession s,long id)throws Exception{return body(mvc.perform(post("/api/loan-installments/{id}/confirm",id).session(s).with(csrf())).andExpect(status().isOk()).andReturn()).path("data").path("confirmedTransactionId").asLong();}
 private long prepay(MockHttpSession s,long id,String amount,String key)throws Exception{return body(mvc.perform(post("/api/loans/{id}/prepay",id).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\""+amount+"\",\"paidOn\":\"2026-09-03\",\"idempotencyKey\":\""+key+"\"}")).andExpect(status().isOk()).andReturn()).path("data").path("transactionId").asLong();}
 private long create(MockHttpSession s)throws Exception {long a=jdbc.queryForObject("select min(id) from financial_accounts where household_id=1",Long.class),c=jdbc.queryForObject("select min(id) from categories where household_id=1 and kind='EXPENSE'",Long.class),m=jdbc.queryForObject("select min(id) from family_members where household_id=1",Long.class),u=jdbc.queryForObject("select id from app_users where email='demo@local.family'",Long.class);String b="{\"name\":\"提前还款\",\"type\":\"OTHER\",\"memberId\":"+m+",\"assignedUserId\":"+u+",\"paymentAccountId\":"+a+",\"paymentCategoryId\":"+c+",\"principal\":\"1000.00\",\"annualRate\":0.120000,\"termMonths\":3,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2024-01-01\"}";return body(mvc.perform(post("/api/loans").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();}
 private MockHttpSession login()throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);} private JsonNode body(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
}
