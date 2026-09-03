package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.family.HouseholdRole;
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
class LoanApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Test void ownersCreateAndMembersReadButCannotMutateOrLinkCrossHouseholdAssets() throws Exception {
  MockHttpSession owner=login("demo","demo1234"), member=join(owner,"loan-member@example.com",HouseholdRole.MEMBER);
  long account=jdbc.queryForObject("select min(id) from financial_accounts where household_id=1",Long.class);
  long category=jdbc.queryForObject("select min(id) from categories where household_id=1 and kind='EXPENSE'",Long.class);
  long id=create(owner,body(account,category));
  mvc.perform(get("/api/loans/{id}",id).session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentPrincipal").value("1000.00"));
  mvc.perform(patch("/api/loans/{id}",id).session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"不能改\"}")) .andExpect(status().isForbidden());
  mvc.perform(get("/api/loans/{id}/schedule",id).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3)).andExpect(jsonPath("$.data[2].principal").value("336.66"));
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=?",Long.class,id)).isEqualTo(3L);
 }
 private long create(MockHttpSession s,String body)throws Exception{return node(mvc.perform(post("/api/loans").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();}
 private String body(long account,long category){return "{\"name\":\"房贷\",\"type\":\"MORTGAGE\",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0.120000,\"termMonths\":3,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2024-01-31\"}";}
 private MockHttpSession join(MockHttpSession owner,String email,HouseholdRole role)throws Exception{String token=node(mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\""+role+"\"}")).andExpect(status().isCreated()).andReturn()).path("data").path("token").asText();mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"贷款成员\",\"password\":\"family-pass-2026\",\"mode\":\"JOIN\",\"inviteToken\":\""+token+"\"}")) .andExpect(status().isCreated());return login(email,"family-pass-2026");}
 private MockHttpSession login(String u,String p)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",u).param("password",p)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 private JsonNode node(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
}
