package com.familyfinance.household;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Transactional
class MemberApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Autowired
    FinancialAccountRepository accountRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Test
    void listCreateUpdateAndDeleteMembersWithinCurrentHousehold() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/members").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].name").value("Kevin"))
                .andExpect(jsonPath("$.data[4].name").value("奶奶"));

        MvcResult created = mvc.perform(post("/api/members")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "  奶奶  ",
                                  "roleLabel": "照护者"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("奶奶"))
                .andExpect(jsonPath("$.data.roleLabel").value("照护者"))
                .andReturn();

        Long memberId = JsonTestUtils.readId(created);

        mvc.perform(patch("/api/members/{id}", memberId)
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "奶奶",
                                  "roleLabel": "家庭长辈"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleLabel").value("家庭长辈"));

        mvc.perform(delete("/api/members/{id}", memberId)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingReferencedMemberReturnsResourceInUse() throws Exception {
        MockHttpSession session = login();
        Household otherHousehold = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(otherHousehold).get(0);
        Category category = categoryRepository.findByHouseholdOrderById(otherHousehold).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();

        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                otherHousehold,
                member,
                category,
                TransactionKind.EXPENSE,
                1000L,
                LocalDate.parse("2026-09-01"),
                "超市",
                "杭州",
                "成员引用保护",
                TEST_TIME,
                TEST_TIME));

        mvc.perform(delete("/api/members/{id}", member.getId())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void deletingMemberReferencedOnlyByBudgetHistoryNamesTheActualReference() throws Exception {
        MockHttpSession session = login();
        MvcResult createdMember = mvc.perform(post("/api/members")
                        .session(session).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"历史预算成员\",\"roleLabel\":\"成员\"}"))
                .andExpect(status().isCreated()).andReturn();
        long memberId = JsonTestUtils.readId(createdMember);
        MvcResult createdBudget = mvc.perform(post("/api/budgets")
                        .session(session).with(csrf()).contentType("application/json")
                        .content("""
                                {"periodMonth":"2029-01","scopeType":"MEMBER","memberId":%d,"amount":"100.00"}
                                """.formatted(memberId)))
                .andExpect(status().isCreated()).andReturn();
        long budgetId = JsonTestUtils.readId(createdBudget);
        int version = JsonTestUtils.readInt(createdBudget, "version");
        mvc.perform(patch("/api/budgets/{id}", budgetId)
                        .session(session).with(csrf()).contentType("application/json")
                        .content("{\"version\":" + version + ",\"scopeType\":\"TOTAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").isEmpty());

        mvc.perform(delete("/api/members/{id}", memberId).session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"))
                .andExpect(jsonPath("$.error.message").value("该成员已被预算或预算历史使用，无法删除"));
    }

    @Test
    void memberFromAnotherHouseholdReturnsNotFound() throws Exception {
        MockHttpSession session = login();
        Household outsider = householdRepository.save(new Household("其他家庭", TEST_TIME));
        FamilyMember outsiderMember = memberRepository.save(new FamilyMember(outsider, "外部成员", "访客", TEST_TIME));

        mvc.perform(patch("/api/members/{id}", outsiderMember.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "外部成员",
                                  "roleLabel": "修改失败"
                                }
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/members/{id}", outsiderMember.getId())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static final class JsonTestUtils {

        private JsonTestUtils() {
        }

        static Long readId(MvcResult result) throws Exception {
            String body = result.getResponse().getContentAsString();
            int index = body.indexOf("\"id\":");
            int start = index + 5;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) {
                end++;
            }
            return Long.valueOf(body.substring(start, end));
        }

        static int readInt(MvcResult result, String field) throws Exception {
            String body = result.getResponse().getContentAsString();
            int index = body.indexOf("\"" + field + "\":");
            int start = index + field.length() + 3;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
            return Integer.parseInt(body.substring(start, end));
        }
    }
}
