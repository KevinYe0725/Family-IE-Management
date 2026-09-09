package com.familyfinance.investment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.familyfinance.market.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.seed.enabled=true","app.multicurrency.enabled=true"}) @AutoConfigureMockMvc @ActiveProfiles("test")
class OverseasInvestmentApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
 @MockitoBean MarketDataClient client;
 MockHttpSession login()throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 @Test void resolvingVerifiedInstrumentIsIdempotentAndPreservesItsCurrency()throws Exception{
  when(client.overseasSearch("HK","00700")).thenReturn(new OverseasSearchResponse(List.of(new OverseasInstrument("00700","腾讯控股","HK","HKD","HKEX","Asia/Hong_Kong")),false,Instant.now(),false,"READY",null));
  var session=login();String body="{\"market\":\"HK\",\"symbol\":\"00700\"}";
  var first=mvc.perform(post("/api/securities/overseas/resolve").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.currency").value("HKD")).andExpect(jsonPath("$.data.symbol").value("00700")).andReturn();
  long id=json.readTree(first.getResponse().getContentAsString()).path("data").path("id").asLong();
  mvc.perform(post("/api/securities/overseas/resolve").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(jsonPath("$.data.id").value(id));
  assertThat(jdbc.queryForObject("select count(*) from securities where market='HK' and ts_code='00700.HK'",Integer.class)).isEqualTo(1);
 }
 @Test void aUsdHongKongCounterCannotBeSilentlyRegisteredAsHkd()throws Exception{
  when(client.overseasSearch("HK","08001")).thenReturn(new OverseasSearchResponse(List.of(new OverseasInstrument("08001","USD Counter","HK","USD","HKEX","Asia/Hong_Kong")),false,Instant.now(),false,"READY",null));
  mvc.perform(post("/api/securities/overseas/resolve").session(login()).with(csrf()).contentType("application/json").content("{\"market\":\"HK\",\"symbol\":\"08001\"}"))
    .andExpect(status().isBadRequest());
  assertThat(jdbc.queryForObject("select count(*) from securities where ts_code='08001.HK'",Integer.class)).isZero();
 }
 @Test void membersMaySelectPublicWatchQuotesWithoutReceivingInvestmentWritePermission()throws Exception{
  when(client.overseasSearch("US","AAPL")).thenReturn(new OverseasSearchResponse(List.of(new OverseasInstrument("AAPL","Apple","US","USD","NASDAQ","America/New_York")),false,Instant.now(),false,"READY",null));
  var session=login();long user=json.readTree(mvc.perform(get("/api/session").session(session)).andReturn().getResponse().getContentAsString()).path("data").path("userId").asLong();
  String previous=jdbc.queryForObject("select role from household_memberships where user_id=?",String.class,user);
  long trades=jdbc.queryForObject("select count(*) from investment_trades",Long.class);
  try{
   jdbc.update("update household_memberships set role='MEMBER' where user_id=?",user);
   String body="{\"market\":\"US\",\"symbol\":\"AAPL\"}";
   mvc.perform(post("/api/securities/overseas/resolve").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
   mvc.perform(post("/api/securities/overseas/watch").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.symbol").value("AAPL"));
   assertThat(jdbc.queryForObject("select count(*) from investment_trades",Long.class)).isEqualTo(trades);
  }finally{jdbc.update("update household_memberships set role=? where user_id=?",previous,user);}
 }
}
