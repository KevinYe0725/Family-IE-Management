package com.familyfinance.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties="app.seed.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FamilyPeopleApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void combinesLinkedAndOfflinePeopleWithoutDuplicatingOrRenamingLedgerIdentity() throws Exception {
        var session=login();
        long household=jdbc.queryForObject("select household_id from app_users where email='demo@local.family'",Long.class);
        long before=jdbc.queryForObject("select count(*) from family_members where household_id=?",Long.class,household);
        String body=mvc.perform(get("/api/family/people").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var people=json.readTree(body).path("data");
        assertThat(people.size()).isEqualTo((int)before);
        int linked=0,offline=0;
        for(var person:people){
            if(person.path("loginStatus").asText().equals("AVAILABLE")) {
                linked++;
                assertThat(person.path("name").asText()).isEqualTo("Kevin");
                assertThat(person.path("accountDisplayName").asText()).isEqualTo("演示用户");
                assertThat(person.path("role").asText()).isEqualTo("OWNER");
            } else {
                offline++;
                assertThat(person.path("membershipId").isNull()).isTrue();
                assertThat(person.path("role").isNull()).isTrue();
            }
        }
        assertThat(linked).isEqualTo(1);
        assertThat(offline).isEqualTo((int)before-1);
        assertThat(jdbc.queryForObject("select count(*) from family_members where household_id=?",Long.class,household)).isEqualTo(before);
    }

    @Test
    void includesLegacyLoginWithoutMemberAndNeverShowsAnotherHouseholdOrDisabledLoginAsAvailable() throws Exception {
        var session=login();
        long h=jdbc.queryForObject("select household_id from app_users where email='demo@local.family'",Long.class);
        // Legacy login with no ledger-person row must not disappear from the directory.
        jdbc.update("insert into app_users(household_id,username,email,display_name,password_hash,created_at,status) values(?,?,?,?,?,CURRENT_TIMESTAMP,'ACTIVE')",h,"legacy-person","legacy-person@test.local","旧账号","unused");
        long user=jdbc.queryForObject("select id from app_users where username='legacy-person'",Long.class);
        jdbc.update("insert into household_memberships(household_id,user_id,role,status,joined_at) values(?,?,'MEMBER','SUSPENDED',CURRENT_TIMESTAMP)",h,user);
        jdbc.update("insert into households(name,created_at,status) values('其他家庭',CURRENT_TIMESTAMP,'ACTIVE')");
        long other=jdbc.queryForObject("select id from households where name='其他家庭'",Long.class);
        jdbc.update("insert into family_members(household_id,name,role_label,created_at) values(?,'不可泄露','长辈',CURRENT_TIMESTAMP)",other);
        var people=json.readTree(mvc.perform(get("/api/family/people").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        boolean found=false;
        for(var person:people){
            assertThat(person.path("name").asText()).isNotEqualTo("不可泄露");
            if(person.path("name").asText().equals("旧账号")){
                found=true;
                assertThat(person.path("loginStatus").asText()).isEqualTo("SUSPENDED");
                assertThat(person.path("memberId").isNull()).isTrue();
            }
        }
        assertThat(found).isTrue();
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
