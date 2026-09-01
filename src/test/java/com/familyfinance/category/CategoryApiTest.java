package com.familyfinance.category;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
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
class CategoryApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    FinancialTransactionRepository transactionRepository;

    @Test
    void listCreateUpdateAndDeleteCategoriesWithinCurrentHousehold() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/categories").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].kind").value("income"))
                .andExpect(jsonPath("$.data[2].kind").value("expense"));

        MvcResult created = mvc.perform(post("/api/categories")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "  宠物  ",
                                  "color": "#112233"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("expense"))
                .andExpect(jsonPath("$.data.name").value("宠物"))
                .andExpect(jsonPath("$.data.color").value("#112233"))
                .andReturn();

        Long categoryId = JsonTestUtils.readId(created);

        mvc.perform(patch("/api/categories/{id}", categoryId)
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "宠物用品",
                                  "color": "#445566"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("宠物用品"))
                .andExpect(jsonPath("$.data.color").value("#445566"));

        mvc.perform(delete("/api/categories/{id}", categoryId)
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidKindReturnsValidationFieldError() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/categories")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "transfer",
                                  "name": "转账",
                                  "color": "#112233"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.kind").exists());
    }

    @Test
    void invalidColorReturnsValidationFieldError() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/categories")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "日用品",
                                  "color": "blue"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.color").exists());
    }

    @Test
    void categoryFromAnotherHouseholdReturnsNotFound() throws Exception {
        MockHttpSession session = login();
        Household outsider = householdRepository.save(new Household("其他家庭", TEST_TIME));
        Category outsiderCategory = categoryRepository.save(
                new Category(outsider, TransactionKind.EXPENSE, "外部分类", "#123456", false, TEST_TIME));

        mvc.perform(patch("/api/categories/{id}", outsiderCategory.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "修改失败",
                                  "color": "#654321"
                                }
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/categories/{id}", outsiderCategory.getId())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingReferencedCategoryReturnsResourceInUse() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();

        transactionRepository.save(new FinancialTransaction(
                household,
                member,
                category,
                TransactionKind.EXPENSE,
                3000L,
                LocalDate.parse("2026-09-02"),
                "便利店",
                "杭州",
                "分类引用保护",
                TEST_TIME,
                TEST_TIME));

        mvc.perform(delete("/api/categories/{id}", category.getId())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
    }

    @Test
    void duplicateNameWithinSameHouseholdAndKindReturnsConflict() throws Exception {
        MockHttpSession session = login();

        mvc.perform(post("/api/categories")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "餐饮",
                                  "color": "#112233"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("同一收支类型下的分类名称不能重复"));
    }

    @Test
    void changingReferencedCategoryKindReturnsResourceInUse() throws Exception {
        MockHttpSession session = login();
        Category category = categoryRepository.findByHouseholdOrderById(householdRepository.findAll().get(0)).stream()
                .filter(saved -> saved.getName().equals("餐饮"))
                .findFirst()
                .orElseThrow();

        mvc.perform(patch("/api/categories/{id}", category.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "income",
                                  "name": "餐饮",
                                  "color": "#D8664B"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_IN_USE"));
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
    }
}
