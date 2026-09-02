package com.familyfinance.transaction;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class TransactionConflictTranslationApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @MockitoSpyBean
    FinancialTransactionRepository transactionRepository;

    @Autowired
    FinancialAccountRepository accountRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @Test
    void createTranslatesAnIntegrityFailureAtSaveAndFlush() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = expenseCategory(household);
        Mockito.doThrow(new DataIntegrityViolationException("forced create conflict"))
                .when(transactionRepository)
                .saveAndFlush(Mockito.any(FinancialTransaction.class));

        mvc.perform(post("/api/transactions")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(request(member.getId(), category.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("收支记录关联的数据已变化，请刷新后重试"));
    }

    @Test
    void updateTranslatesAnIntegrityFailureAtFlush() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = expenseCategory(household);
        FinancialTransaction transaction = transactionRepository.saveAndFlush(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                member,
                category,
                TransactionKind.EXPENSE,
                1230L,
                LocalDate.parse("2026-09-18"),
                "商家",
                "杭州",
                "更新前",
                TEST_TIME,
                TEST_TIME));
        Mockito.doThrow(new DataIntegrityViolationException("forced update conflict"))
                .when(transactionRepository)
                .flush();

        mvc.perform(patch("/api/transactions/{id}", transaction.getId())
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                { "note": "更新后" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("收支记录关联的数据已变化，请刷新后重试"));
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

    private Category expenseCategory(Household household) {
        return categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();
    }

    private static String request(Long memberId, Long categoryId) {
        return """
                {
                  "kind": "expense",
                  "amount": "12.30",
                  "occurredOn": "2026-09-18",
                  "memberId": %d,
                  "categoryId": %d,
                  "note": "冲突翻译"
                }
                """.formatted(memberId, categoryId);
    }
}
