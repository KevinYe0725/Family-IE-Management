package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MembershipPaginationApiTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired HouseholdRepository households;
    @Autowired HouseholdMembershipRepository memberships;
    @Autowired Clock clock;

    @Test
    void membershipPagesClampSizeOrderByDescendingIdAndNeverCrossHouseholds() throws Exception {
        AppUser demo = users.findByEmail("demo@local.family").orElseThrow();
        Household household = demo.getHousehold();
        List<Long> createdIds = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            String email = "paged-member-" + index + "@example.com";
            AppUser user = users.save(new AppUser(
                    household, email, email, "分页成员" + index, demo.getPasswordHash(), clock.instant()));
            createdIds.add(memberships.save(new HouseholdMembership(
                    household, user, HouseholdRole.MEMBER, MembershipStatus.ACTIVE, clock.instant())).getId());
        }

        Household foreignHousehold = households.save(new Household("外部家庭", clock.instant()));
        AppUser foreignUser = users.save(new AppUser(
                foreignHousehold,
                "foreign-page-owner@example.com",
                "foreign-page-owner@example.com",
                "外部所有者",
                demo.getPasswordHash(),
                clock.instant()));
        long foreignMembershipId = memberships.save(new HouseholdMembership(
                foreignHousehold, foreignUser, HouseholdRole.OWNER, MembershipStatus.ACTIVE, clock.instant())).getId();

        MockHttpSession owner = login();
        MvcResult firstPage = mvc.perform(get("/api/family/memberships")
                        .param("page", "-4").param("size", "999").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(56))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(50))
                .andReturn();

        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(firstPage).path("data").path("items")) {
            ids.add(item.path("id").asLong());
        }
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ids).contains(createdIds.get(createdIds.size() - 1));
        assertThat(ids).doesNotContain(foreignMembershipId);

        mvc.perform(get("/api/family/memberships").param("page", "1").param("size", "50").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(6))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/api/family/memberships").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }
}
