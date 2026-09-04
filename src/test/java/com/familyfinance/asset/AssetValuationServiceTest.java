package com.familyfinance.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(AssetValuationServiceTest.FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AssetValuationServiceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean AssetValuationRepository valuations;

    @Test
    void creationAlwaysPersistsCurrentValueAsTodaysManualBaselineWithOrWithoutPurchaseFacts() throws Exception {
        MockHttpSession owner = login();
        long purchased = createOther(owner, "多年以前购入", "500.00", "550.00", "2024-01-01");
        long gifted = createOther(owner, "无购买价值", null, "88.00", null);

        JsonNode history = body(mvc.perform(get("/api/assets/{id}/valuations", purchased).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].valuedOn").value("2026-09-03"))
                .andExpect(jsonPath("$.data.items[0].value").value("550.00"))
                .andExpect(jsonPath("$.data.items[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.data.items[0].fetchedAt").value("2026-09-03T02:00:00Z"))
                .andReturn()).path("data").path("items").get(0);
        assertThat(history.path("createdBy").asLong()).isEqualTo(jdbc.queryForObject(
                "select created_by from assets where id=?", Long.class, purchased));
        assertThat(jdbc.queryForObject("""
                select count(*) from asset_valuations
                where asset_id=? and valued_on=date '2026-09-03'
                  and value_cents=8800 and source='MANUAL' and created_by=1
                """, Long.class, gifted)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from asset_valuations where asset_id=? and source='PURCHASE'",
                Long.class, purchased)).isZero();
    }

    @Test
    void creationCurrentBaselinePreventsAChronologicallyLaterButBackdatedFactFromRegressingCurrentValue()
            throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "历史回填资产", "500.00", "550.00", "2024-01-01");

        createValuation(owner, assetId, "2025-01-01", "400.00", "补录旧估值");

        assertCurrent(assetId, 55_000L);
    }

    @Test
    void creationRollsBackBaseAndSubtypeWhenCurrentBaselineCannotBePersisted() throws Exception {
        MockHttpSession owner = login();
        long assetsBefore = jdbc.queryForObject("select count(*) from assets", Long.class);
        long vehiclesBefore = jdbc.queryForObject("select count(*) from vehicle_assets", Long.class);
        long valuationsBefore = jdbc.queryForObject("select count(*) from asset_valuations", Long.class);
        Mockito.doThrow(new DataIntegrityViolationException("forced creation baseline conflict"))
                .when(valuations).saveAndFlush(Mockito.any(AssetValuation.class));

        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"应整体回滚车辆","type":"VEHICLE","ownerMemberId":null,
                                 "acquiredOn":null,"purchaseValue":null,"currentValue":"180000.00",
                                 "vehicle":{"brandModel":"回滚车型","plateHint":null,"purchaseYear":2025}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"));

        assertThat(jdbc.queryForObject("select count(*) from assets", Long.class)).isEqualTo(assetsBefore);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_assets", Long.class)).isEqualTo(vehiclesBefore);
        assertThat(jdbc.queryForObject("select count(*) from asset_valuations", Long.class))
                .isEqualTo(valuationsBefore);
    }

    @Test
    void acquiredOnChangesFromFutureToTodayExactlyAtShanghaiMidnight() throws Exception {
        MockHttpSession owner = login();
        clock.setInstant(Instant.parse("2026-09-02T15:59:59Z"));
        String body = """
                {"name":"上海零点资产","type":"OTHER","ownerMemberId":null,
                 "acquiredOn":"2026-09-03","purchaseValue":null,"currentValue":"1.00"}
                """;
        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.acquiredOn").exists());

        clock.setInstant(Instant.parse("2026-09-02T16:00:00Z"));
        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.acquiredOn").value("2026-09-03"));
    }

    @Test
    void localTodayValuationReplacesInPlaceExactlyAtShanghaiMidnight() throws Exception {
        MockHttpSession owner = login();
        clock.setInstant(Instant.parse("2026-09-02T15:59:59Z"));
        long assetId = createOther(owner, "上海零点估值", null, "88.00", null);
        jdbc.update("""
                insert into asset_valuations
                    (household_id,asset_id,valued_on,value_cents,source,note,created_by,fetched_at)
                values (1,?,date '2026-09-03',8800,'MANUAL','零点前预置',1,
                        timestamp with time zone '2026-09-02 15:59:59+00')
                """, assetId);
        long valuationId = jdbc.queryForObject("""
                select id from asset_valuations
                where asset_id=? and valued_on=date '2026-09-03' and source='MANUAL'
                """, Long.class, assetId);

        clock.setInstant(Instant.parse("2026-09-02T16:00:00Z"));
        long replacedId = createValuation(owner, assetId, "2026-09-03", "99.00", "零点后修订");

        assertThat(replacedId).isEqualTo(valuationId);
        assertThat(jdbc.queryForObject("select created_by from asset_valuations where id=?", Long.class, valuationId))
                .isEqualTo(1L);
        assertCurrent(assetId, 9_900L);
    }

    @Test
    void manualCurrentDayValuationReplacesInPlaceWhileOlderDatesStayImmutable() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "估值历史资产", "100.00", "100.00", "2026-08-01");
        long creationBaselineId = jdbc.queryForObject("""
                select id from asset_valuations
                where asset_id=? and valued_on=date '2026-09-03' and source='MANUAL'
                """, Long.class, assetId);
        long creationBaselineCreator = jdbc.queryForObject(
                "select created_by from asset_valuations where id=?", Long.class, creationBaselineId);
        long firstManualId = createValuation(owner, assetId, "2026-09-03", "200.00", "上午估值");
        long creatorId = jdbc.queryForObject(
                "select created_by from asset_valuations where id=?", Long.class, firstManualId);
        Instant firstFetchedAt = jdbc.queryForObject(
                "select fetched_at from asset_valuations where id=?", Instant.class, firstManualId);
        clock.advanceSeconds(60);

        long replacementId = createValuation(owner, assetId, "2026-09-03", "210.00", "下午估值");

        assertThat(firstManualId).isEqualTo(creationBaselineId);
        assertThat(replacementId).isEqualTo(firstManualId);
        assertThat(creatorId).isEqualTo(creationBaselineCreator);
        assertThat(jdbc.queryForObject("select created_by from asset_valuations where id=?", Long.class, firstManualId))
                .isEqualTo(creatorId);
        assertThat(jdbc.queryForObject("select fetched_at from asset_valuations where id=?", Instant.class, firstManualId))
                .isAfter(firstFetchedAt);
        assertThat(jdbc.queryForObject("select value_cents from asset_valuations where id=?", Long.class, firstManualId))
                .isEqualTo(21_000L);

        createValuation(owner, assetId, "2026-09-01", "150.00", "历史估值");
        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valuationBody("2026-09-01", "151.00", "改写历史")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VALUATION_IMMUTABLE"));
    }

    @Test
    void latestOrderingUsesValuedOnThenFetchedAtThenIdAndBackdatingNeverRegressesCurrentValue() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "最新估值资产", "100.00", "110.00", "2026-09-03");
        long backdatedId = createValuation(owner, assetId, "2026-08-31", "999.00", "回填历史");
        assertCurrent(assetId, 11_000L);

        long todayId = createValuation(owner, assetId, "2026-09-03", "200.00", "今日估值");
        assertCurrent(assetId, 20_000L);
        clock.advanceSeconds(1);
        long replacedId = createValuation(owner, assetId, "2026-09-03", "190.00", "今日修订");
        assertThat(replacedId).isEqualTo(todayId);
        assertCurrent(assetId, 19_000L);

        JsonNode items = body(mvc.perform(get("/api/assets/{id}/valuations", assetId).session(owner)
                        .param("page", "-5").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(header().string("X-Page-Size", "50"))
                .andReturn()).path("data").path("items");
        assertThat(items.get(0).path("id").asLong()).isEqualTo(todayId);
        assertThat(items.get(0).path("source").asText()).isEqualTo("MANUAL");
        assertThat(items.get(0).path("fetchedAt").asText()).isNotBlank();
        assertThat(items.get(1).path("id").asLong()).isEqualTo(backdatedId);
    }

    @Test
    void valuationValidationRejectsFutureNegativeOverflowBlankNotesAndBoundsHistory() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "估值校验资产", null, "0.00", null);
        List<String> invalidBodies = List.of(
                valuationBody("2026-09-04", "1.00", null),
                valuationBody("2026-09-02", "-0.01", null),
                valuationBody("2026-09-02", "10000000000.00", null),
                valuationBody("2026-09-02", "1.001", null),
                valuationBody("2026-09-02", "1.00", "   "),
                valuationBody("2026-09-02", "1.00", "x".repeat(501)));
        for (String body : invalidBodies) {
            mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        for (int index = 0; index < 52; index++) {
            jdbc.update("""
                    insert into asset_valuations
                        (household_id,asset_id,valued_on,value_cents,source,note,created_by,fetched_at)
                    values (1,?,dateadd('DAY', ?, date '2026-01-01'),?,'MANUAL',null,1,dateadd('SECOND', ?, timestamp '2026-01-01 00:00:00+00'))
                    """, assetId, index, (long) index, index);
        }

        JsonNode items = body(mvc.perform(get("/api/assets/{id}/valuations", assetId).session(owner)
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn()).path("data").path("items");
        List<String> dates = new ArrayList<>();
        items.forEach(item -> dates.add(item.path("valuedOn").asText()));
        assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void valuationAcceptsTenBillionYuanBoundaryValue() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "大额估值资产", null, "0.00", null);

        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valuationBody("2026-09-03", "9999999999", "大额估值")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.value").value("9999999999.00"));
        assertCurrent(assetId, 999_999_999_900L);
    }

    @Test
    void valuationWriteRegeneratesStaleAssetReminder() throws Exception {
        MockHttpSession owner = login();
        clock.setInstant(Instant.parse("2026-08-01T02:00:00Z"));
        long assetId = createOther(owner, "过期估值资产", null, "100.00", null);
        jdbc.update("update asset_valuations set valued_on=date '2026-07-01' where asset_id=?", assetId);
        clock.setInstant(Instant.parse("2026-09-04T02:00:00Z"));

        createValuation(owner, assetId, "2026-08-01", "110.00", "补录估值");

        mvc.perform(get("/api/notifications").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("ASSET_VALUATION_STALE"))
                .andExpect(jsonPath("$.data.items[0].referenceId").value(assetId));
    }

    @Test
    void concurrentCurrentDayRequestsLeaveOneManualFactAndNoServerError() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "并发估值资产", null, "0.00", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> atBarrier(ready, start,
                    () -> postValuation(owner, assetId, "2026-09-03", "300.00", "并发一")));
            Future<MvcResult> second = executor.submit(() -> atBarrier(ready, start,
                    () -> postValuation(owner, assetId, "2026-09-03", "400.00", "并发二")));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .stream().map(result -> result.getResponse().getStatus()).toList();
            assertThat(statuses).allMatch(code -> code == 201 || code == 409);
            assertThat(statuses).noneMatch(code -> code >= 500);
            assertThat(jdbc.queryForObject("""
                    select count(*) from asset_valuations
                    where asset_id=? and valued_on=date '2026-09-03' and source='MANUAL'
                    """, Long.class, assetId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select current_value_cents from assets where id=?", Long.class, assetId))
                    .isIn(30_000L, 40_000L);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void databaseDuplicateIsTranslatedAndRollsBackWithoutChangingTheProjection() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "唯一冲突资产", null, "88.00", null);
        Mockito.doThrow(new DataIntegrityViolationException("forced valuation unique conflict"))
                .when(valuations).saveAndFlush(Mockito.any(AssetValuation.class));

        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valuationBody("2026-09-03", "100.00", "当前请求")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VALUATION_CONFLICT"));

        assertThat(jdbc.queryForObject("select count(*) from asset_valuations where asset_id=?", Long.class, assetId))
                .isEqualTo(1L);
        assertCurrent(assetId, 8_800L);
    }

    @Test
    void currentValueFailureRollsBackTheNewValuation() throws Exception {
        MockHttpSession owner = login();
        long assetId = createOther(owner, "回滚估值资产", null, "100.00", null);
        long beforeCount = jdbc.queryForObject(
                "select count(*) from asset_valuations where asset_id=?", Long.class, assetId);
        jdbc.execute("alter table assets add constraint test_reject_projected_value check (current_value_cents <> 77777)");

        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valuationBody("2026-09-03", "777.77", "触发投影失败")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VALUATION_PROJECTION_CONFLICT"));

        assertThat(jdbc.queryForObject("select count(*) from asset_valuations where asset_id=?", Long.class, assetId))
                .isEqualTo(beforeCount);
        assertCurrent(assetId, 10_000L);
    }

    private MvcResult postValuation(
            MockHttpSession session, long assetId, String date, String value, String note) throws Exception {
        return mvc.perform(post("/api/assets/{id}/valuations", assetId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(valuationBody(date, value, note)))
                .andReturn();
    }

    private long createValuation(
            MockHttpSession session, long assetId, String date, String value, String note) throws Exception {
        return body(mvc.perform(post("/api/assets/{id}/valuations", assetId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(valuationBody(date, value, note)))
                .andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();
    }

    private long createOther(
            MockHttpSession session, String name, String purchaseValue, String currentValue, String acquiredOn)
            throws Exception {
        String purchase = purchaseValue == null ? "null" : "\"" + purchaseValue + "\"";
        String acquired = acquiredOn == null ? "null" : "\"" + acquiredOn + "\"";
        String body = """
                {"name":"%s","type":"OTHER","ownerMemberId":null,"acquiredOn":%s,
                 "purchaseValue":%s,"currentValue":"%s"}
                """.formatted(name, acquired, purchase, currentValue);
        return body(mvc.perform(post("/api/assets").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();
    }

    private void assertCurrent(long assetId, long expected) {
        assertThat(jdbc.queryForObject("select current_value_cents from assets where id=?", Long.class, assetId))
                .isEqualTo(expected);
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String valuationBody(String date, String value, String note) {
        String noteField = note == null ? "" : ",\"note\":" + quote(note);
        return "{\"valuedOn\":\"" + date + "\",\"value\":\"" + value + "\"" + noteField + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static MvcResult atBarrier(
            CountDownLatch ready, CountDownLatch start, ThrowingRequest request) throws Exception {
        ready.countDown();
        await(start);
        return request.run();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent request did not resume");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRequest { MvcResult run() throws Exception; }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean @Primary MutableClock assetClock() {
            return new MutableClock(Instant.parse("2026-09-03T02:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;
        MutableClock(Instant initial) { this(new AtomicReference<>(initial), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }
        void setInstant(Instant next) { instant.set(next); }
        void advanceSeconds(long seconds) { instant.updateAndGet(current -> current.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId nextZone) { return new MutableClock(instant, nextZone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
