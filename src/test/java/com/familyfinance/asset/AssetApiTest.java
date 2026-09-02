package com.familyfinance.asset;

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
import com.familyfinance.household.FamilyMemberRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
class AssetApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FamilyMemberRepository members;
    @Autowired JdbcTemplate jdbc;

    @Test
    void propertyAndVehicleEnforceTypedSubtypeRulesAndOnlyAdminsCanMutate() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        MockHttpSession admin = join(owner, uniqueEmail("asset-admin"), HouseholdRole.ADMIN);
        MockHttpSession member = join(owner, uniqueEmail("asset-member"), HouseholdRole.MEMBER);
        long ownerMemberId = firstMemberId();

        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyBody("缺面积房产", ownerMemberId, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields['property.areaSqm']").exists());
        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleBody("缺车型车辆", ownerMemberId, "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields['vehicle.brandModel']").exists());
        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherBody("重复现金", ownerMemberId).replace("\"OTHER\"", "\"CASH\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assets").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyBody("成员房产", ownerMemberId, "88.25")))
                .andExpect(status().isForbidden());

        long propertyId = create(owner, propertyBody("自住房", ownerMemberId, "88.25"));
        long vehicleId = create(admin, vehicleBody("家用车", ownerMemberId, "示例 S"));
        long otherId = create(owner, otherBody("收藏品", null));

        mvc.perform(get("/api/assets/{id}", propertyId).session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("PROPERTY"))
                .andExpect(jsonPath("$.data.purchaseValue").value("500000.00"))
                .andExpect(jsonPath("$.data.currentValue").value("550000.00"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdBy").isNumber())
                .andExpect(jsonPath("$.data.property.address").value("杭州市西湖区 1 号"))
                .andExpect(jsonPath("$.data.property.areaSqm").value(88.25))
                .andExpect(jsonPath("$.data.vehicle").doesNotExist());
        mvc.perform(get("/api/assets/{id}/valuations", propertyId).session(member))
                .andExpect(status().isOk());
        mvc.perform(post("/api/assets/{id}/valuations", propertyId).session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valuedOn\":\"2026-09-01\",\"value\":\"1.00\"}"))
                .andExpect(status().isForbidden());
        createValuation(admin, propertyId, "2026-09-01", "560000.00");
        assertThat(jdbc.queryForObject("select count(*) from property_assets where asset_id=?", Long.class, propertyId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_assets where asset_id=?", Long.class, vehicleId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from property_assets where asset_id=?", Long.class, otherId))
                .isZero();
    }

    @Test
    void ownerMustBelongToCurrentHouseholdAndCrossHouseholdResourcesNeverLeak() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        registerCreate("foreign-assets@example.com", "外部资产家庭");
        MockHttpSession foreign = login("foreign-assets@example.com", "family-pass-2026");
        long foreignMemberId = jdbc.queryForObject("select id from family_members where household_id=2", Long.class);
        long foreignAssetId = create(foreign, otherBody("外部藏品", foreignMemberId));

        mvc.perform(post("/api/assets").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherBody("越界所有者", foreignMemberId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.ownerMemberId").exists());

        String foreignGet = mvc.perform(get("/api/assets/{id}", foreignAssetId).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingGet = mvc.perform(get("/api/assets/{id}", Long.MAX_VALUE).session(owner))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreignGet).isEqualTo(missingGet).doesNotContain("外部藏品").doesNotContain("外部资产家庭");
        mvc.perform(patch("/api/assets/{id}", foreignAssetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/assets/{id}", foreignAssetId).session(owner).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/{id}/valuations", foreignAssetId).session(owner))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/assets/{id}/valuations", foreignAssetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valuedOn\":\"2026-09-01\",\"value\":\"1.00\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void assetFieldsHonorDatabaseLengthsDatesAndExactNumericBounds() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long memberId = firstMemberId();
        String validOther = otherBody("边界资产", memberId);
        List<String> invalid = List.of(
                validOther.replace("\"边界资产\"", "\"   \""),
                validOther.replace("\"边界资产\"", "\"" + "x".repeat(101) + "\""),
                validOther.replace("\"120.00\"", "\"-0.01\""),
                validOther.replace("\"120.00\"", "\"1000000000.00\""),
                validOther.replace("\"120.00\"", "\"1.001\""),
                validOther.replace("\"2023-03-04\"", "\"2026-09-04\""),
                validOther.replace("}", ",\"property\":{\"address\":\"x\",\"areaSqm\":\"1.00\",\"usageType\":\"SELF_USE\"}}"),
                propertyBody("零面积", memberId, "0"),
                propertyBody("面积小数越界", memberId, "1.001"),
                propertyBody("面积科学计数法", memberId, "1e2"),
                vehicleBody("年份越界", memberId, "示例车").replace("2025}", "1885}"),
                vehicleBody("空车牌", memberId, "示例车").replace("浙A***01", "   "));
        for (String body : invalid) {
            mvc.perform(post("/api/assets").session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
        }

        String exactMax = validOther
                .replace("\"100.00\"", "\"999999999.99\"")
                .replace("\"120.00\"", "\"999999999.99\"");
        long id = create(owner, exactMax);
        mvc.perform(get("/api/assets/{id}", id).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchaseValue").value("999999999.99"))
                .andExpect(jsonPath("$.data.currentValue").value("999999999.99"));
    }

    @Test
    void patchUpdatesOnlyMutableProfileAndPreservesHistoricalAndCreatorFields() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long ownerMemberId = firstMemberId();
        long assetId = create(owner, propertyBody("旧房产", ownerMemberId, "88.25"));
        long createdBy = jdbc.queryForObject("select created_by from assets where id=?", Long.class, assetId);

        mvc.perform(patch("/api/assets/{id}", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新房产","ownerMemberId":null,
                                 "property":{"address":"杭州市滨江区 2 号","areaSqm":"99.50","usageType":"RENTAL"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新房产"))
                .andExpect(jsonPath("$.data.ownerMemberId").doesNotExist())
                .andExpect(jsonPath("$.data.property.address").value("杭州市滨江区 2 号"))
                .andExpect(jsonPath("$.data.property.usageType").value("RENTAL"));

        mvc.perform(patch("/api/assets/{id}", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicle\":{\"brandModel\":\"错误车型\"}}"))
                .andExpect(status().isUnprocessableEntity());
        for (String immutable : List.of(
                "{\"type\":\"OTHER\"}",
                "{\"acquiredOn\":\"2025-01-01\"}",
                "{\"purchaseValue\":\"1.00\"}",
                "{\"currentValue\":\"1.00\"}",
                "{\"createdBy\":999}",
                "{\"status\":\"ARCHIVED\"}")) {
            mvc.perform(patch("/api/assets/{id}", assetId).session(owner).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(immutable))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/assets/{id}", assetId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("PROPERTY"))
                .andExpect(jsonPath("$.data.acquiredOn").value("2024-01-02"))
                .andExpect(jsonPath("$.data.purchaseValue").value("500000.00"))
                .andExpect(jsonPath("$.data.currentValue").value("550000.00"))
                .andExpect(jsonPath("$.data.createdBy").value(createdBy));
    }

    @Test
    void listIsBoundedStableAndFilterableByTypeAndStatus() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long memberId = firstMemberId();
        List<Long> created = new ArrayList<>();
        for (int index = 0; index < 52; index++) {
            created.add(create(owner, otherBody("分页资产-" + index, memberId)));
        }
        long propertyId = create(owner, propertyBody("筛选房产", memberId, "66.66"));
        mvc.perform(delete("/api/assets/{id}", created.get(0)).session(owner).with(csrf()))
                .andExpect(status().isNoContent());

        JsonNode items = body(mvc.perform(get("/api/assets").session(owner)
                        .param("page", "-1").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andReturn()).path("data").path("items");
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());

        mvc.perform(get("/api/assets").session(owner).param("type", "PROPERTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(propertyId));
        mvc.perform(get("/api/assets").session(owner).param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(created.get(0)));
    }

    @Test
    void archiveIsIdempotentAndRetainsSubtypeAndValuationHistory() throws Exception {
        MockHttpSession owner = login("demo", "demo1234");
        long assetId = create(owner, propertyBody("历史房产", firstMemberId(), "88.25"));
        createValuation(owner, assetId, "2026-09-01", "600000.00");
        jdbc.execute("create table loans (id bigint primary key, household_id bigint not null, linked_asset_id bigint)");
        jdbc.update("insert into loans (id,household_id,linked_asset_id) values (1,1,?)", assetId);

        mvc.perform(delete("/api/assets/{id}", assetId).session(owner).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
        jdbc.update("delete from loans where linked_asset_id=?", assetId);

        mvc.perform(delete("/api/assets/{id}", assetId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());
        String firstArchivedAt = jdbc.queryForObject(
                "select cast(archived_at as varchar) from assets where id=?", String.class, assetId);
        mvc.perform(delete("/api/assets/{id}", assetId).session(owner).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select cast(archived_at as varchar) from assets where id=?", String.class, assetId))
                .isEqualTo(firstArchivedAt);
        assertThat(jdbc.queryForObject("select count(*) from assets where id=?", Long.class, assetId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from property_assets where asset_id=?", Long.class, assetId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from asset_valuations where asset_id=?", Long.class, assetId))
                .isEqualTo(2L);
        mvc.perform(get("/api/assets/{id}/valuations", assetId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valuedOn\":\"2026-09-02\",\"value\":\"1.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ASSET_ARCHIVED"));
    }

    private long create(MockHttpSession session, String requestBody) throws Exception {
        return body(mvc.perform(post("/api/assets").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated()).andReturn()).path("data").path("id").asLong();
    }

    private void createValuation(MockHttpSession session, long assetId, String date, String value) throws Exception {
        mvc.perform(post("/api/assets/{id}/valuations", assetId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valuedOn\":\"" + date + "\",\"value\":\"" + value + "\"}"))
                .andExpect(status().isCreated());
    }

    private long firstMemberId() {
        return members.findByHouseholdIdOrderById(1L).get(0).getId();
    }

    private MockHttpSession join(MockHttpSession owner, String email, HouseholdRole role) throws Exception {
        MvcResult invite = mvc.perform(post("/api/family/invites").session(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String token = body(invite).path("data").path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"资产协作者","password":"family-pass-2026",
                                 "mode":"JOIN","inviteToken":"%s"}
                                """.formatted(email, token)))
                .andExpect(status().isCreated());
        return login(email, "family-pass-2026");
    }

    private void registerCreate(String email, String householdName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"外部所有者","password":"family-pass-2026",
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

    private static String propertyBody(String name, Long memberId, String area) {
        String owner = memberId == null ? "null" : memberId.toString();
        String areaField = area == null ? "" : ",\"areaSqm\":\"" + area + "\"";
        return """
                {"name":"%s","type":"PROPERTY","ownerMemberId":%s,"acquiredOn":"2024-01-02",
                 "purchaseValue":"500000.00","currentValue":"550000.00",
                 "property":{"address":"杭州市西湖区 1 号"%s,"usageType":"SELF_USE"}}
                """.formatted(name, owner, areaField);
    }

    private static String vehicleBody(String name, Long memberId, String brandModel) {
        String owner = memberId == null ? "null" : memberId.toString();
        return """
                {"name":"%s","type":"VEHICLE","ownerMemberId":%s,"acquiredOn":"2025-02-03",
                 "purchaseValue":"200000.00","currentValue":"180000.00",
                 "vehicle":{"brandModel":"%s","plateHint":"浙A***01","purchaseYear":2025}}
                """.formatted(name, owner, brandModel);
    }

    private static String otherBody(String name, Long memberId) {
        String owner = memberId == null ? "null" : memberId.toString();
        return """
                {"name":"%s","type":"OTHER","ownerMemberId":%s,"acquiredOn":"2023-03-04",
                 "purchaseValue":"100.00","currentValue":"120.00"}
                """.formatted(name, owner);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + '-' + Long.toUnsignedString(System.nanoTime(), 36) + "@example.com";
    }
}
