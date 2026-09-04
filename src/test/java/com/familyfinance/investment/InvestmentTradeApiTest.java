package com.familyfinance.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.family.HouseholdRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InvestmentTradeApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void investmentAccountCrudArchiveAndPagingAreHouseholdScopedAndAdminOnly() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, uniqueEmail("invest-admin"), HouseholdRole.ADMIN);
        MockHttpSession member = join(owner, uniqueEmail("invest-member"), HouseholdRole.MEMBER);
        long creator = currentUserId("demo@local.family");

        mvc.perform(post("/api/investment-accounts").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody("成员账户")))
                .andExpect(status().isForbidden());
        long accountId = createAccount(owner, "主投资账户");
        mvc.perform(get("/api/investment-accounts/{id}", accountId).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("主投资账户"))
                .andExpect(jsonPath("$.data.brokerName").value("测试券商"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdBy").value(creator));

        mvc.perform(patch("/api/investment-accounts/{id}", accountId).session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"长期账户\",\"brokerName\":\"新券商\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("长期账户"))
                .andExpect(jsonPath("$.data.brokerName").value("新券商"))
                .andExpect(jsonPath("$.data.createdBy").value(creator));
        for (String immutable : List.of(
                "{\"currency\":\"USD\"}",
                "{\"createdBy\":999}",
                "{\"archivedAt\":\"2026-01-01T00:00:00Z\"}")) {
            mvc.perform(patch("/api/investment-accounts/{id}", accountId).session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(immutable))
                    .andExpect(status().isBadRequest());
        }

        for (int index = 0; index < 52; index++) {
            jdbc.update("""
                    insert into investment_accounts
                        (household_id,name,broker_name,currency,archived_at,created_by)
                    values (1,?,?,'CNY',null,?)
                    """, "分页账户-" + index, "券商-" + index, creator);
        }
        JsonNode items = body(mvc.perform(get("/api/investment-accounts").session(member)
                        .param("page", "-1").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andExpect(header().string("X-Page-Size", "50"))
                .andReturn()).path("data").path("items");
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());

        mvc.perform(delete("/api/investment-accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        String archivedAt = jdbc.queryForObject(
                "select cast(archived_at as varchar) from investment_accounts where id=?", String.class, accountId);
        mvc.perform(delete("/api/investment-accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "select cast(archived_at as varchar) from investment_accounts where id=?", String.class, accountId))
                .isEqualTo(archivedAt);
        mvc.perform(get("/api/investment-accounts").session(member).param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(accountId));
    }

    @Test
    void localSecurityResolveNormalizesValidAShareCodesAndConcurrentDuplicatesConverge() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession member = join(owner, uniqueEmail("security-member"), HouseholdRole.MEMBER);
        registerCreate("security-other@example.com", "证券二号家庭");
        MockHttpSession otherOwner = login("security-other@example.com", "family-pass-2026");

        long securityId = resolveSecurity(owner, " 600000.sh ", " 浦发银行 ");
        mvc.perform(get("/api/securities/search").session(member).param("q", "浦发"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(securityId))
                .andExpect(jsonPath("$.data.items[0].market").value("SH"))
                .andExpect(jsonPath("$.data.items[0].tsCode").value("600000.SH"));
        mvc.perform(get("/api/securities/search").session(member).param("q", "600000.sh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(securityId));
        for (int index = 0; index < 25; index++) {
            String code = "%06d.SH".formatted(700000 + index);
            jdbc.update("""
                    insert into securities (market,ts_code,name,security_type,active)
                    values ('SH',?,?,'STOCK',true)
                    """, code, "稳定排序证券-" + index);
        }
        JsonNode searchItems = body(mvc.perform(get("/api/securities/search").session(member)
                        .param("q", "").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(header().string("X-Has-Next", "true"))
                .andReturn()).path("data").path("items");
        List<String> codes = new ArrayList<>();
        searchItems.forEach(item -> codes.add(item.path("tsCode").asText()));
        assertThat(codes).isSorted();
        mvc.perform(post("/api/securities/resolve").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tsCode\":\"000001.SZ\",\"name\":\"平安银行\"}"))
                .andExpect(status().isForbidden());
        for (String invalidCode : List.of("12345.SZ", "600000.HK", "600000.SH.extra")) {
            mvc.perform(post("/api/securities/resolve").session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tsCode\":\"" + invalidCode + "\",\"name\":\"错误代码\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.fields.tsCode").exists());
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return resolveSecurity(owner, "000001.sz", "平安银行");
            });
            Future<Long> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return resolveSecurity(otherOwner, "000001.SZ", "平安银行");
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from securities where ts_code='000001.SZ'", Long.class)).isEqualTo(1L);

        long accountId = createAccount(owner, "代码直录账户");
        mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":%d,"tsCode":"300750.sz","securityName":"宁德时代",
                                 "type":"BUY","quantity":"1.0000","price":"100.00","fee":"0.00",
                                 "tradedOn":"2026-01-01"}
                                """.formatted(accountId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.trade.security.tsCode").value("300750.SZ"))
                .andExpect(jsonPath("$.data.position.quantity").value(1.0000));
    }

    @Test
    void inactiveSharedSecurityReturnsSafeConflictForResolveAndCodeBasedTradeEntry() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long accountId = createAccount(owner, "停用证券账户");
        long securityId = resolveSecurity(owner, "000001.SZ", "平安银行");
        jdbc.update("update securities set active=false where id=?", securityId);

        for (String endpointBody : List.of(
                "{\"tsCode\":\"000001.sz\",\"name\":\"任意名称\"}",
                """
                        {"accountId":%d,"tsCode":"000001.sz","securityName":"任意名称",
                         "type":"BUY","quantity":"1.0000","price":"1.00","fee":"0.00",
                         "tradedOn":"2026-01-01"}
                        """.formatted(accountId))) {
            boolean resolve = endpointBody.startsWith("{\"tsCode");
            MvcResult result = mvc.perform(post(resolve ? "/api/securities/resolve" : "/api/investment-trades")
                            .session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(endpointBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SECURITY_INACTIVE"))
                    .andExpect(jsonPath("$.error.message").value("证券当前不可用"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("平安银行")
                    .doesNotContain("任意名称")
                    .doesNotContain("IllegalStateException");
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from securities where ts_code='000001.SZ'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select active from securities where id=?", Boolean.class, securityId)).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from investment_trades where account_id=?", Long.class, accountId)).isZero();
    }

    @Test
    void tradesCalculatePositionsEnforceHistoryAndExposeStableFilteredPages() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession member = join(owner, uniqueEmail("trade-member"), HouseholdRole.MEMBER);
        long accountId = createAccount(owner, "收益账户");
        long securityId = resolveSecurity(owner, "600000.SH", "浦发银行");

        long firstBuy = createTrade(owner, tradeBody(accountId, securityId, "BUY", "100.0000", "10.00", "1.00", "2026-01-01"));
        createTrade(owner, tradeBody(accountId, securityId, "BUY", "50.0000", "12.00", "0.50", "2026-01-02"));
        long sellId = createTrade(owner, tradeBody(accountId, securityId, "SELL", "60.0000", "15.00", "0.80", "2026-01-03"));
        MvcResult dividend = mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(accountId, securityId, "DIVIDEND", null, "3.00", "0.00", "2026-01-04")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.position.quantity").value(90.0000))
                .andExpect(jsonPath("$.data.position.cost").value("960.90"))
                .andExpect(jsonPath("$.data.position.averageCost").value("10.6767"))
                .andExpect(jsonPath("$.data.position.realizedProfit").value("261.60"))
                .andExpect(jsonPath("$.data.position.cashImpact").value("-699.30"))
                .andReturn();
        assertThat(body(dividend).path("data").path("position").path("marketValue").isNull()).isTrue();
        mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(accountId, securityId, "FEE", null, "0.25", "0.00", "2026-01-04")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.position.realizedProfit").value("261.35"));

        mvc.perform(get("/api/investment-trades").session(member)
                        .param("accountId", Long.toString(accountId))
                        .param("securityId", Long.toString(securityId))
                        .param("type", "SELL")
                        .param("from", "2026-01-03").param("to", "2026-01-03")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(sellId))
                .andExpect(jsonPath("$.data.items[0].cashImpact").value("899.20"));

        mvc.perform(patch("/api/investment-trades/{id}", firstBuy).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"5.0000\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_HOLDING"));
        assertThat(jdbc.queryForObject(
                "select quantity from investment_trades where id=?", java.math.BigDecimal.class, firstBuy))
                .isEqualByComparingTo("100.0000");
        mvc.perform(delete("/api/investment-trades/{id}", firstBuy).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_HOLDING"));
        assertThat(jdbc.queryForObject("select count(*) from investment_trades where id=?", Long.class, firstBuy))
                .isEqualTo(1L);

        long creator = jdbc.queryForObject(
                "select created_by from investment_trades where id=?", Long.class, sellId);
        mvc.perform(patch("/api/investment-trades/{id}", sellId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"50.0000\",\"createdBy\":999}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "select created_by from investment_trades where id=?", Long.class, sellId)).isEqualTo(creator);

        mvc.perform(patch("/api/investment-trades/{id}", sellId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"50.0000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position.quantity").value(100.0000));
        mvc.perform(delete("/api/investment-trades/{id}", firstBuy).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from investment_trades where id=?", Long.class, firstBuy))
                .isZero();
    }

    @Test
    void crossAccountAndSecurityMoveRollsBackInvalidTargetThenReplaysBothPositions() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long originAccount = createAccount(owner, "原账户");
        long targetAccount = createAccount(owner, "目标账户");
        long originSecurity = resolveSecurity(owner, "600000.SH", "浦发银行");
        long targetSecurity = resolveSecurity(owner, "000001.SZ", "平安银行");
        long originBuy = createTrade(owner, tradeBody(
                originAccount, originSecurity, "BUY", "100.0000", "10.00", "0.00", "2026-01-01"));
        long movedSell = createTrade(owner, tradeBody(
                originAccount, originSecurity, "SELL", "60.0000", "15.00", "0.00", "2026-01-02"));
        createTrade(owner, tradeBody(
                targetAccount, targetSecurity, "BUY", "20.0000", "10.00", "0.00", "2026-01-01"));
        String before = tradeRow(movedSell);

        mvc.perform(patch("/api/investment-trades/{id}", movedSell).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + targetAccount + ",\"securityId\":" + targetSecurity + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_HOLDING"));
        assertThat(tradeRow(movedSell)).isEqualTo(before);

        createTrade(owner, tradeBody(
                targetAccount, targetSecurity, "BUY", "80.0000", "10.00", "0.00", "2026-01-01"));

        mvc.perform(patch("/api/investment-trades/{id}", movedSell).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + targetAccount + ",\"securityId\":" + targetSecurity + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position.accountId").value(targetAccount))
                .andExpect(jsonPath("$.data.position.security.id").value(targetSecurity))
                .andExpect(jsonPath("$.data.position.quantity").value(40.0000))
                .andExpect(jsonPath("$.data.position.cost").value("400.00"))
                .andExpect(jsonPath("$.data.trade.createdBy").value(1))
                .andExpect(jsonPath("$.data.trade.sourceType").value("MANUAL"));
        mvc.perform(patch("/api/investment-trades/{id}", originBuy).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position.accountId").value(originAccount))
                .andExpect(jsonPath("$.data.position.security.id").value(originSecurity))
                .andExpect(jsonPath("$.data.position.quantity").value(100.0000))
                .andExpect(jsonPath("$.data.position.cost").value("1000.00"));
        assertThat(tradeRow(movedSell)).contains("|1|MANUAL|NULL");
    }

    @Test
    void tradeValidationArchiveCrossHouseholdAndImportedHistoryFailClosed() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession member = join(owner, uniqueEmail("trade-ro"), HouseholdRole.MEMBER);
        long accountId = createAccount(owner, "约束账户");
        long securityId = resolveSecurity(owner, "000001.SZ", "平安银行");

        mvc.perform(post("/api/investment-trades").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(accountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-01")))
                .andExpect(status().isForbidden());
        for (String invalid : List.of(
                tradeBody(accountId, securityId, "BUY", "1.00001", "1.00", "0.00", "2026-01-01"),
                tradeBody(accountId, securityId, "SELL", "1.0000", "1.00", "0.00", "2026-01-01"),
                tradeBody(accountId, securityId, "DIVIDEND", "1.0000", "1.00", "0.00", "2026-01-01"),
                tradeBody(accountId, securityId, "BUY", "1.0000", "1000000000.00", "0.00", "2026-01-01"),
                tradeBody(accountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-09-05"))) {
            mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(409, 422));
        }
        mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(accountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-01")
                                .replace("}", ",\"sourceType\":\"IMPORT\",\"sourceId\":\"external-1\"}")))
                .andExpect(status().isUnprocessableEntity());

        registerCreate("trade-foreign@example.com", "交易外部家庭");
        MockHttpSession foreign = login("trade-foreign@example.com", "family-pass-2026");
        long foreignAccount = createAccount(foreign, "外部账户");
        long foreignTrade = createTrade(foreign, tradeBody(
                foreignAccount, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-01"));
        String foreignResult = mvc.perform(get("/api/investment-accounts/{id}", foreignAccount).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingResult = mvc.perform(get("/api/investment-accounts/{id}", Long.MAX_VALUE).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreignResult).isEqualTo(missingResult).doesNotContain("外部账户");
        String foreignTradeResult = mvc.perform(get("/api/investment-trades/{id}", foreignTrade).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingTradeResult = mvc.perform(get("/api/investment-trades/{id}", Long.MAX_VALUE).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreignTradeResult).isEqualTo(missingTradeResult).doesNotContain("trade-foreign");
        mvc.perform(patch("/api/investment-trades/{id}", foreignTrade).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"2.00\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/investment-trades/{id}", foreignTrade).session(owner).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(foreignAccount, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-01")))
                .andExpect(status().isUnprocessableEntity());

        createTrade(owner, tradeBody(accountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-01"));
        mvc.perform(delete("/api/investment-accounts/{id}", accountId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/investment-trades").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeBody(accountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-02")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVESTMENT_ACCOUNT_ARCHIVED"));
        long activeAccountId = createAccount(owner, "仍有效账户");
        long activeTradeId = createTrade(owner, tradeBody(
                activeAccountId, securityId, "BUY", "1.0000", "1.00", "0.00", "2026-01-02"));
        mvc.perform(patch("/api/investment-trades/{id}", activeTradeId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + accountId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVESTMENT_ACCOUNT_ARCHIVED"));

        long importedId = jdbc.queryForObject("""
                select coalesce(max(id),0)+1 from investment_trades
                """, Long.class);
        long userId = currentUserId("demo@local.family");
        jdbc.update("""
                insert into investment_trades
                    (id,household_id,account_id,security_id,trade_type,quantity,price_cents,
                     fee_cents,traded_on,created_by,source_type,source_id)
                values (?,1,?,?,'DIVIDEND',null,100,0,date '2026-01-03',?,'IMPORT','import-1')
                """, importedId, accountId, securityId, userId);
        mvc.perform(patch("/api/investment-trades/{id}", importedId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"2.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORTED_TRADE_IMMUTABLE"));
        mvc.perform(delete("/api/investment-trades/{id}", importedId).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPORTED_TRADE_IMMUTABLE"));
    }

    private long createAccount(MockHttpSession session, String name) throws Exception {
        return body(mvc.perform(post("/api/investment-accounts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(accountBody(name)))
                .andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();
    }

    private long resolveSecurity(MockHttpSession session, String code, String name) throws Exception {
        return body(mvc.perform(post("/api/securities/resolve").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tsCode\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn()).path("data").path("id").asLong();
    }

    private long createTrade(MockHttpSession session, String requestBody) throws Exception {
        return body(mvc.perform(post("/api/investment-trades").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated()).andReturn()).path("data").path("trade").path("id").asLong();
    }

    private long currentUserId(String email) {
        return jdbc.queryForObject("select id from app_users where email=?", Long.class, email);
    }

    private String tradeRow(long tradeId) {
        return jdbc.queryForObject("""
                select concat(account_id,'|',security_id,'|',trade_type,'|',
                              coalesce(cast(quantity as varchar),'NULL'),'|',price_cents,'|',fee_cents,'|',
                              cast(traded_on as varchar),'|',created_by,'|',source_type,'|',coalesce(source_id,'NULL'))
                from investment_trades where id=?
                """, String.class, tradeId);
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = body(invite).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"投资协作者","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private void registerCreate(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"投资外部所有者","password":"family-pass-2026",
                                 "mode":"CREATE","householdName":"%s"}
                                """.formatted(email, householdName)))
                .andExpect(status().isCreated());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String accountBody(String name) {
        return "{\"name\":\"" + name + "\",\"brokerName\":\"测试券商\",\"currency\":\"cny\"}";
    }

    private static String tradeBody(
            long accountId,
            long securityId,
            String type,
            String quantity,
            String price,
            String fee,
            String date) {
        String quantityField = quantity == null ? "null" : "\"" + quantity + "\"";
        return """
                {"accountId":%d,"securityId":%d,"type":"%s","quantity":%s,
                 "price":"%s","fee":"%s","tradedOn":"%s"}
                """.formatted(accountId, securityId, type, quantityField, price, fee, date);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime(), 36) + "@example.com";
    }
}
