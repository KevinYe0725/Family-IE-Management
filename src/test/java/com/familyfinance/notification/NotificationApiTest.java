package com.familyfinance.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.family.HouseholdRole;
import java.time.LocalDate;
import java.time.YearMonth;
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
class NotificationApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Test void generationRerunIsIdempotentAndReadResolveRemainHouseholdScoped() throws Exception {
  MockHttpSession owner=login(); long loan=createDueLoan(owner);
  mvc.perform(post("/api/notifications/generate").session(owner).with(csrf())).andExpect(status().isOk());
  mvc.perform(post("/api/notifications/generate").session(owner).with(csrf())).andExpect(status().isOk());
  long id=body(mvc.perform(get("/api/notifications").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1)).andExpect(jsonPath("$.data.unreadCount").value(1)).andReturn()).path("data").path("items").get(0).path("id").asLong();
  mvc.perform(post("/api/notifications/{id}/read",id).session(owner).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").exists());
  mvc.perform(post("/api/notifications/{id}/resolve",id).session(owner).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.resolvedAt").exists());
  assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='LOAN_INSTALLMENT'",Long.class)).isEqualTo(1L);
  assertThat(jdbc.queryForObject("select id from loans where id=?",Long.class,loan)).isEqualTo(loan);
 }

 @Test void memberBudgetReminderIsVisibleOnlyToTheTargetUser() throws Exception {
  MockHttpSession owner=login();
  String email="budget-reminder-member@example.com";
  MockHttpSession member=join(owner,email,HouseholdRole.MEMBER);
  long memberId=jdbc.queryForObject("select id from family_members where linked_user_id=(select id from app_users where email=?)",Long.class,email);
  long accountId=jdbc.queryForObject("select min(id) from financial_accounts where household_id=1",Long.class);
  long categoryId=jdbc.queryForObject("select min(id) from categories where household_id=1 and kind='EXPENSE'",Long.class);
  String month=YearMonth.now().toString(), today=LocalDate.now().toString();
  mvc.perform(post("/api/budgets").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content("{\"periodMonth\":\""+month+"\",\"scopeType\":\"MEMBER\",\"memberId\":"+memberId+",\"amount\":\"1.00\"}"))
      .andExpect(status().isCreated());
  mvc.perform(post("/api/transactions").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content("{\"kind\":\"EXPENSE\",\"amount\":\"2.00\",\"occurredOn\":\""+today+"\",\"accountId\":"+accountId+",\"memberId\":"+memberId+",\"categoryId\":"+categoryId+"}"))
      .andExpect(status().isCreated());
  mvc.perform(post("/api/notifications/generate").session(owner).with(csrf())).andExpect(status().isOk());

  String ownerNotifications=mvc.perform(get("/api/notifications").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  String memberNotifications=mvc.perform(get("/api/notifications").session(member)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(ownerNotifications).doesNotContain("BUDGET_LIMIT");
  assertThat(memberNotifications).contains("BUDGET_LIMIT");
 }

 private MockHttpSession join(MockHttpSession owner,String email,HouseholdRole role)throws Exception {
  String token=body(mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
          .content("{\"role\":\""+role+"\"}"))
      .andExpect(status().isCreated()).andReturn()).path("data").path("token").asText();
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
      .content("{\"email\":\""+email+"\",\"displayName\":\"预算成员\",\"password\":\"family-pass-2026\",\"mode\":\"JOIN\",\"inviteToken\":\""+token+"\"}"))
      .andExpect(status().isCreated());
  MvcResult login=mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","family-pass-2026"))
      .andExpect(status().isOk()).andReturn();
  return (MockHttpSession)login.getRequest().getSession(false);
 }
 private long createDueLoan(MockHttpSession s)throws Exception {long a=jdbc.queryForObject("select min(id) from financial_accounts where household_id=1",Long.class),c=jdbc.queryForObject("select min(id) from categories where household_id=1 and kind='EXPENSE'",Long.class),m=jdbc.queryForObject("select min(id) from family_members where household_id=1",Long.class),u=jdbc.queryForObject("select id from app_users where email='demo@local.family'",Long.class);String b="{\"name\":\"提醒贷款\",\"type\":\"OTHER\",\"memberId\":"+m+",\"assignedUserId\":"+u+",\"paymentAccountId\":"+a+",\"paymentCategoryId\":"+c+",\"principal\":\"1000.00\",\"annualRate\":0.000000,\"termMonths\":1,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2024-01-01\"}";return body(mvc.perform(post("/api/loans").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();}
 private MockHttpSession login()throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);} private JsonNode body(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
}
