package com.familyfinance.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.budget.Budget;
import com.familyfinance.budget.BudgetRepository;
import com.familyfinance.budget.BudgetScopeType;
import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
class ConsolidatedReportingApiTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;
    @Autowired BudgetRepository budgets;
    @Autowired FinancialAccountRepository accounts;
    @Autowired FinancialTransactionRepository transactions;

    @Test
    void netWorthBudgetUsesStoredCentsAndMatchesUserFacingCategoryRollup() throws Exception {
        MockHttpSession session = login();
        var household = users.findByUsername("demo").orElseThrow().getHousehold();
        var member = members.findByHouseholdOrderById(household).get(0);
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        Category parent = categories.saveAndFlush(new Category(
                household, TransactionKind.EXPENSE, "汇总父分类", "#123456", false, now));
        Category child = categories.saveAndFlush(new Category(
                household, TransactionKind.EXPENSE, "汇总子分类", "#123456", false, parent, now));
        Budget budget = budgets.saveAndFlush(new Budget(
                household, YearMonth.of(2026, 9), BudgetScopeType.CATEGORY, parent, null, 159_136L));
        transactions.saveAndFlush(TransactionTestFixtures.newTransaction(
                accounts, users, household, member, parent, TransactionKind.EXPENSE, 159_135L,
                LocalDate.of(2026, 9, 2), null, null, "整数分预算", now, now));
        transactions.saveAndFlush(TransactionTestFixtures.newTransaction(
                accounts, users, household, member, child, TransactionKind.EXPENSE, 1L,
                LocalDate.of(2026, 9, 3), null, null, "一分子分类", now, now));

        com.familyfinance.accounting.AccountingTestFixtures.postFixtureTransactions(context,household.getId());
        mvc.perform(get("/api/net-worth").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budget.planned").value("1591.36"))
                .andExpect(jsonPath("$.data.budget.spent").value("1591.36"))
                .andExpect(jsonPath("$.data.budget.nearLimitCount").value(0))
                .andExpect(jsonPath("$.data.budget.overLimitCount").value(1));
        mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2026-09").param("rollupCategories", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].budget.id").value(budget.getId()))
                .andExpect(jsonPath("$.data[0].spent").value("1591.35"))
                .andExpect(jsonPath("$.data[0].status").value("NEAR_LIMIT"))
                .andExpect(jsonPath("$.data[0].rollupCategories").value(false));
        mvc.perform(get("/api/budgets/usage").session(session)
                        .param("periodMonth", "2026-09")
                        .param("rollupCategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].spent").value("1591.36"))
                .andExpect(jsonPath("$.data[0].status").value("AT_LIMIT"))
                .andExpect(jsonPath("$.data[0].rollupCategories").value(true));
    }

    @Test
    void netWorthAndDebtEndpointsExposeBoundedServerCalculatedViews() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/api/net-worth").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asset").isString())
                .andExpect(jsonPath("$.data.liability").isString())
                .andExpect(jsonPath("$.data.netWorth").isString())
                .andExpect(jsonPath("$.data.allocation").isArray())
                .andExpect(jsonPath("$.data.investment.manualPrice").isBoolean())
                .andExpect(jsonPath("$.data.history").isArray());
        mvc.perform(get("/api/debt-analysis").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.debtRatioPercent").isString())
                .andExpect(jsonPath("$.data.loans").isArray());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
