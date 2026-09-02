package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.ledger.AccountType;
import com.familyfinance.ledger.FinancialAccount;
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

    @Autowired
    FinancialAccountRepository accountRepository;

    @Autowired
    AppUserRepository appUserRepository;

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

        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
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

    @Test
    void exportCsvNeutralizesSpreadsheetFormulaPrefixesBeforeQuoting() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category food = categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .filter(saved -> saved.getName().equals("餐饮"))
                .findFirst()
                .orElseThrow();

        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                member,
                food,
                TransactionKind.EXPENSE,
                1230L,
                LocalDate.parse("2026-09-22"),
                "=2+2",
                "+SUM(1,1)",
                "-危险公式",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
                household,
                member,
                food,
                TransactionKind.EXPENSE,
                2340L,
                LocalDate.parse("2026-09-23"),
                "@HYPERLINK",
                "\t制表符公式",
                "普通备注",
                TEST_TIME,
                TEST_TIME));

        MvcResult result = mvc.perform(get("/api/export.csv")
                        .session(session)
                        .param("from", "2026-09-22")
                        .param("to", "2026-09-23")
                        .param("kind", "expense"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).contains("2026-09-22,支出,12.30,Kevin,餐饮,'=2+2,\"'+SUM(1,1)\",'-危险公式");
        assertThat(csv).contains("2026-09-23,支出,23.40,Kevin,餐饮,'@HYPERLINK,'\t制表符公式,普通备注");
    }

    @Test
    void exportWithInvalidCategoryIdReturnsFieldValidationError() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/export.csv")
                        .session(session)
                        .param("categoryId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code")
                        .value("VALIDATION_ERROR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.fields.categoryId")
                        .value("参数必须是数字"));
    }

    @Test
    void archivedAccountFilterWorksAndCsvExportsEveryMatchBeyondUiPageBounds() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category food = categoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();
        var creator = appUserRepository.findByEmail("demo@local.family").orElseThrow();
        FinancialAccount archivedAccount = accountRepository.save(new FinancialAccount(
                household, "CSV 历史账户", AccountType.BANK, "CNY", 0L));
        FinancialAccount otherAccount = accountRepository.save(new FinancialAccount(
                household, "CSV 其他账户", AccountType.WALLET, "CNY", 0L));
        String marker = "csv-account-complete";
        for (int index = 0; index < 55; index++) {
            transactionRepository.save(new FinancialTransaction(
                    household,
                    archivedAccount,
                    creator,
                    member,
                    food,
                    TransactionKind.EXPENSE,
                    100L + index,
                    LocalDate.of(2026, 9, 1).plusDays(index % 20),
                    null,
                    null,
                    marker + "-" + index,
                    TEST_TIME,
                    TEST_TIME));
        }
        transactionRepository.save(new FinancialTransaction(
                household,
                otherAccount,
                creator,
                member,
                food,
                TransactionKind.EXPENSE,
                999L,
                LocalDate.of(2026, 9, 20),
                null,
                null,
                marker + "-other-account",
                TEST_TIME,
                TEST_TIME));
        transactionRepository.flush();

        mvc.perform(delete("/api/accounts/{id}", archivedAccount.getId()).session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/transactions").session(session)
                        .param("accountId", archivedAccount.getId().toString())
                        .param("q", marker)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(jsonPath("$.data.length()").value(50));

        MvcResult result = mvc.perform(get("/api/export.csv").session(session)
                        .param("accountId", archivedAccount.getId().toString())
                        .param("q", marker))
                .andExpect(status().isOk())
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv.lines().filter(line -> line.contains(marker + "-")).count()).isEqualTo(55);
        assertThat(csv).doesNotContain(marker + "-other-account");
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
