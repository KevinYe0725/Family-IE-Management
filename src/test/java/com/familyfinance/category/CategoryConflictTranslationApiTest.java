package com.familyfinance.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class CategoryConflictTranslationApiTest {

    private static final Instant TEST_TIME = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    HouseholdRepository householdRepository;

    @Autowired
    FamilyMemberRepository memberRepository;

    @Autowired
    CategoryRepository realCategoryRepository;

    @Autowired
    FinancialTransactionRepository realTransactionRepository;

    @MockitoSpyBean
    CategoryRepository categoryRepository;

    @MockitoSpyBean
    FinancialTransactionRepository transactionRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void concurrentDuplicateCreateReturnsConflictInsteadOfGenericFailure() throws Exception {
        String uniqueName = "并发冲突" + System.nanoTime();
        CountDownLatch bothInsideSave = new CountDownLatch(2);

        Mockito.doAnswer(invocation -> {
                    Category category = invocation.getArgument(0);
                    if (uniqueName.equals(category.getName())) {
                        bothInsideSave.countDown();
                        assertThat(bothInsideSave.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    try {
                        entityManager.persist(category);
                        entityManager.flush();
                        return category;
                    } catch (RuntimeException exception) {
                        throw new DataIntegrityViolationException(
                                "concurrent category persistence conflict",
                                exception);
                    }
                })
                .when(categoryRepository)
                .saveAndFlush(Mockito.any(Category.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseSnapshot> first = executor.submit(() -> createCategory(uniqueName));
            Future<ResponseSnapshot> second = executor.submit(() -> createCategory(uniqueName));

            ResponseSnapshot firstResponse = first.get(10, TimeUnit.SECONDS);
            ResponseSnapshot secondResponse = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResponse.status(), secondResponse.status()))
                    .withFailMessage(
                            "saveAndFlush boundary responses: first=%s second=%s",
                            firstResponse.body(),
                            secondResponse.body())
                    .containsExactlyInAnyOrder(201, 409);

            ResponseSnapshot conflict = firstResponse.status() == 409 ? firstResponse : secondResponse;
            assertThat(conflict.body()).contains("\"code\":\"RESOURCE_CONFLICT\"");
            assertThat(conflict.body()).contains("同一收支类型下的分类名称不能重复");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void memberDeleteTranslatesForeignKeyViolationIntoResourceInUseWhenProbeMisses() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = realCategoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();

        realTransactionRepository.save(new FinancialTransaction(
                household,
                member,
                category,
                TransactionKind.EXPENSE,
                1888L,
                LocalDate.parse("2026-09-03"),
                "商店",
                "杭州",
                "成员删除翻译路径",
                TEST_TIME,
                TEST_TIME));

        Mockito.doReturn(false)
                .when(transactionRepository)
                .existsByHouseholdIdAndMemberId(household.getId(), member.getId());

        MvcResult result = mvc.perform(delete("/api/members/{id}", member.getId())
                        .session(session)
                        .with(csrf()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"RESOURCE_IN_USE\"");
    }

    @Test
    void categoryDeleteTranslatesForeignKeyViolationIntoResourceInUseWhenProbeMisses() throws Exception {
        MockHttpSession session = login();
        Household household = householdRepository.findAll().get(0);
        FamilyMember member = memberRepository.findByHouseholdOrderById(household).get(0);
        Category category = realCategoryRepository.findByHouseholdOrderById(household).stream()
                .filter(saved -> saved.getKind() == TransactionKind.EXPENSE)
                .findFirst()
                .orElseThrow();

        realTransactionRepository.save(new FinancialTransaction(
                household,
                member,
                category,
                TransactionKind.EXPENSE,
                2888L,
                LocalDate.parse("2026-09-04"),
                "商店",
                "杭州",
                "分类删除翻译路径",
                TEST_TIME,
                TEST_TIME));

        Mockito.doReturn(false)
                .when(transactionRepository)
                .existsByHouseholdIdAndCategoryId(household.getId(), category.getId());

        MvcResult result = mvc.perform(delete("/api/categories/{id}", category.getId())
                        .session(session)
                        .with(csrf()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"RESOURCE_IN_USE\"");
    }

    private ResponseSnapshot createCategory(String name) throws Exception {
        MockHttpSession session = login();
        MvcResult result = mvc.perform(post("/api/categories")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {
                                  "kind": "expense",
                                  "name": "%s",
                                  "color": "#224466"
                                }
                                """.formatted(name)))
                .andReturn();
        return new ResponseSnapshot(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private record ResponseSnapshot(int status, String body) {
    }
}
