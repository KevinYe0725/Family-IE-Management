package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
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
class TransactionApiTest {

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

    @Test
    void createExpenseStoresIntegerCentsAndReturnsFormattedAmount() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = expenseCategory(household, "餐饮");

        MvcResult created = mvc.perform(post("/api/transactions")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "amount": "12.30",
                                  "occurredOn": "2026-09-18",
                                  "memberId": %d,
                                  "categoryId": %d,
                                  "merchant": "  便利店  ",
                                  "location": "杭州",
                                  "note": "早餐"
                                }
                                """.formatted(member.getId(), category.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("expense"))
                .andExpect(jsonPath("$.data.amount").value("12.30"))
                .andExpect(jsonPath("$.data.merchant").value("便利店"))
                .andReturn();

        Long transactionId = readId(created);
        FinancialTransaction saved = transactionRepository.findById(transactionId).orElseThrow();
        assertThat(saved.getHousehold().getId()).isEqualTo(household.getId());
        assertThat(saved.getAmountCents()).isEqualTo(1230L);
    }

    @Test
    void rejectMismatchedCategoryKind() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category incomeCategory = categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.INCOME)
                .findFirst()
                .orElseThrow();

        mvc.perform(post("/api/transactions")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "amount": "12.30",
                                  "occurredOn": "2026-09-18",
                                  "memberId": %d,
                                  "categoryId": %d,
                                  "merchant": "便利店"
                                }
                                """.formatted(member.getId(), incomeCategory.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.categoryId").value("分类类型必须和收支类型一致"));
    }

    @Test
    void createWithoutDateReturnsValidationFieldError() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = expenseCategory(household, "餐饮");

        mvc.perform(post("/api/transactions")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "amount": "12.30",
                                  "memberId": %d,
                                  "categoryId": %d
                                }
                                """.formatted(member.getId(), category.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.occurredOn").value("日期不能为空"));
    }

    @Test
    void patchMerchantAndNotePreservesOtherFields() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FinancialTransaction transaction = transactionRepository.save(new FinancialTransaction(
                household,
                memberRepository.findByHouseholdOrderById(household).get(0),
                expenseCategory(household, "餐饮"),
                TransactionKind.EXPENSE,
                4560L,
                LocalDate.parse("2026-09-19"),
                "菜场",
                "杭州",
                "午餐",
                TEST_TIME,
                TEST_TIME));

        mvc.perform(patch("/api/transactions/{id}", transaction.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchant": "盒马",
                                  "note": "晚餐食材"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value("45.60"))
                .andExpect(jsonPath("$.data.occurredOn").value("2026-09-19"))
                .andExpect(jsonPath("$.data.merchant").value("盒马"))
                .andExpect(jsonPath("$.data.location").value("杭州"))
                .andExpect(jsonPath("$.data.note").value("晚餐食材"));
    }

    @Test
    void filterSeptemberExpensesByKeyword() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/transactions")
                        .session(session)
                        .param("month", "2026-09")
                        .param("kind", "expense")
                        .param("q", "餐饮"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("expense"))
                .andExpect(jsonPath("$.data[0].amount").value("156.80"))
                .andExpect(jsonPath("$.data[0].note").value("家庭餐饮"));
    }

    @Test
    void transactionFromAnotherHouseholdReturnsNotFound() throws Exception {
        MockHttpSession session = login();
        Household outsider = householdRepository.save(new Household("其他家庭", TEST_TIME));
        FamilyMember outsiderMember = memberRepository.save(new FamilyMember(outsider, "外部成员", "访客", TEST_TIME));
        Category outsiderCategory = categoryRepository.save(
                new Category(outsider, TransactionKind.EXPENSE, "外部分类", "#123456", false, TEST_TIME));
        FinancialTransaction outsiderTransaction = transactionRepository.save(new FinancialTransaction(
                outsider,
                outsiderMember,
                outsiderCategory,
                TransactionKind.EXPENSE,
                9900L,
                LocalDate.parse("2026-09-20"),
                "外部商家",
                "杭州",
                "不应可见",
                TEST_TIME,
                TEST_TIME));

        mvc.perform(get("/api/transactions/{id}", outsiderTransaction.getId()).session(session))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/transactions/{id}", outsiderTransaction.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchant": "修改失败"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTransactionRemovesItFromHousehold() throws Exception {
        MockHttpSession session = login();
        Household household = demoHousehold();
        FinancialTransaction transaction = transactionRepository.save(new FinancialTransaction(
                household,
                memberRepository.findByHouseholdOrderById(household).get(0),
                expenseCategory(household, "餐饮"),
                TransactionKind.EXPENSE,
                8800L,
                LocalDate.parse("2026-09-21"),
                "超市",
                "杭州",
                "待删除",
                TEST_TIME,
                TEST_TIME));

        mvc.perform(delete("/api/transactions/{id}", transaction.getId())
                        .session(session)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(transactionRepository.findById(transaction.getId())).isEmpty();
        mvc.perform(get("/api/transactions/{id}", transaction.getId()).session(session))
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

    private Household demoHousehold() {
        return householdRepository.findAll().get(0);
    }

    private Category expenseCategory(Household household, String name) {
        return categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .filter(saved -> saved.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Long readId(MvcResult result) throws Exception {
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
