package com.familyfinance.market;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.family.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDate;
import java.time.ZoneId;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MarketApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void noTokenDoesNotBlockStartupManualPriceWinsAndMembersCannotWrite() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession member = join(owner, "market-member-" + System.nanoTime() + "@example.com");
        long securityId = resolve(owner, "000001.SZ", "平安银行");
        long accountId = account(owner);
        buy(owner, accountId, securityId);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

        mvc.perform(post("/api/market-quotes/refresh").session(owner).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DISABLED"))
                .andExpect(jsonPath("$.data.error").value("MARKET_DISABLED"));
        mvc.perform(post("/api/securities/{id}/manual-price", securityId).session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":\"11.20\",\"effectiveOn\":\"" + today + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/securities/{id}/manual-price", securityId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":\"11.20\",\"effectiveOn\":\"" + today + "\",\"note\":\"收盘修正\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.price").value("11.20"));
        mvc.perform(get("/api/market-quotes").session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.data[0].price").value("11.20"));
    }

    private long resolve(MockHttpSession session, String code, String name) throws Exception {
        return body(mvc.perform(post("/api/securities/resolve").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tsCode\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn()).path("data").path("id").asLong();
    }
    private long account(MockHttpSession session) throws Exception {
        return body(mvc.perform(post("/api/investment-accounts").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"行情账户\",\"brokerName\":\"测试券商\",\"currency\":\"CNY\"}"))
                .andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();
    }
    private void buy(MockHttpSession session, long account, long security) throws Exception {
        mvc.perform(post("/api/investment-trades").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":%d,\"securityId\":%d,\"type\":\"BUY\",\"quantity\":\"1.0000\",\"price\":\"10.00\",\"fee\":\"0.00\",\"tradedOn\":\"2026-09-01\"}".formatted(account, security)))
                .andExpect(status().isCreated());
    }
    private MockHttpSession join(MockHttpSession owner, String email) throws Exception {
        String token = body(mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + HouseholdRole.MEMBER + "\"}"))
                .andExpect(status().isCreated()).andReturn()).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(
                "{\"email\":\"%s\",\"displayName\":\"行情成员\",\"password\":\"family-pass-2026\",\"mode\":\"JOIN\",\"inviteToken\":\"%s\"}".formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }
    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private JsonNode body(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
}
