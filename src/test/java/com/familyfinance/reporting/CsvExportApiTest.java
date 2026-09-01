package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.nio.charset.StandardCharsets;
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
class CsvExportApiTest {

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
    void exportCsvUsesTransactionFiltersAndEscapesChineseRows() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category food = categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .filter(saved -> saved.getName().equals("餐饮"))
                .findFirst()
                .orElseThrow();

        transactionRepository.save(new FinancialTransaction(
                household,
                member,
                food,
                TransactionKind.EXPENSE,
                1230L,
                LocalDate.parse("2026-09-18"),
                "老王\"面馆,西湖店",
                "杭州",
                "招牌\"拌面\"",
                TEST_TIME,
                TEST_TIME));

        MvcResult result = mvc.perform(get("/api/export.csv")
                        .session(session)
                        .param("month", "2026-09")
                        .param("kind", "expense"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"family-finance.csv\""))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("日期,类型,金额,成员,分类,商家,地点,备注\n");
        assertThat(csv).contains("2026-09-06,支出,156.80,Lily,餐饮,菜场,杭州,家庭餐饮");
        assertThat(csv).contains("2026-09-18,支出,12.30,Kevin,餐饮,\"老王\"\"面馆,西湖店\",杭州,\"招牌\"\"拌面\"\"\"");
        assertThat(csv).doesNotContain("九月工资");
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
}
