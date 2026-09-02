package com.familyfinance.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Transactional
class TransactionPaginationApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FinancialTransactionRepository transactions;
    @Autowired FinancialAccountRepository accounts;
    @Autowired AppUserRepository users;
    @Autowired HouseholdRepository households;
    @Autowired FamilyMemberRepository members;
    @Autowired CategoryRepository categories;

    @Test
    void listClampsBoundsKeepsArrayBodyAndReturnsStablePaginationHeaders() throws Exception {
        MockHttpSession session = login();
        Household household = users.findByEmail("demo@local.family").orElseThrow().getHousehold();
        FamilyMember member = members.findByHouseholdOrderById(household).get(0);
        Category category = expenseCategory(household);
        String marker = "pagination-contract";
        List<FinancialTransaction> expected = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            expected.add(transactions.save(TransactionTestFixtures.newTransaction(
                    accounts,
                    users,
                    household,
                    member,
                    category,
                    TransactionKind.EXPENSE,
                    100L + index,
                    LocalDate.of(2026, 9, 20).plusDays(index % 3),
                    null,
                    null,
                    marker + "-" + index,
                    TEST_TIME,
                    TEST_TIME)));
        }
        Household outsider = households.save(new Household("分页外部家庭", TEST_TIME));
        FamilyMember outsiderMember = members.save(new FamilyMember(outsider, "分页外部成员", "成员", TEST_TIME));
        Category outsiderCategory = categories.save(
                new Category(outsider, TransactionKind.EXPENSE, "分页外部分类", "#123456", false, TEST_TIME));
        transactions.save(TransactionTestFixtures.newTransaction(
                accounts,
                users,
                outsider,
                outsiderMember,
                outsiderCategory,
                TransactionKind.EXPENSE,
                999L,
                LocalDate.of(2026, 9, 30),
                null,
                null,
                marker + "-outsider",
                TEST_TIME,
                TEST_TIME));
        transactions.flush();
        expected.sort(Comparator.comparing(FinancialTransaction::getOccurredOn).reversed()
                .thenComparing(FinancialTransaction::getId, Comparator.reverseOrder()));

        MvcResult firstPage = mvc.perform(get("/api/transactions")
                        .session(session)
                        .param("q", marker)
                        .param("page", "-7")
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$.data.length()").value(50))
                .andReturn();
        assertThat(ids(firstPage)).containsExactlyElementsOf(
                expected.subList(0, 50).stream().map(FinancialTransaction::getId).toList());

        MvcResult secondPage = mvc.perform(get("/api/transactions")
                        .session(session)
                        .param("q", marker)
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "1"))
                .andExpect(header().string("X-Page-Size", "50"))
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andReturn();
        assertThat(ids(secondPage)).containsExactlyElementsOf(
                expected.subList(50, 55).stream().map(FinancialTransaction::getId).toList());

        mvc.perform(get("/api/transactions").session(session).param("q", marker))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "20"))
                .andExpect(header().string("X-Total-Elements", "55"))
                .andExpect(jsonPath("$.data.length()").value(20));
    }

    private List<Long> ids(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        data.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private Category expenseCategory(Household household) {
        return categories.findByHouseholdOrderById(household).stream()
                .filter(category -> category.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
