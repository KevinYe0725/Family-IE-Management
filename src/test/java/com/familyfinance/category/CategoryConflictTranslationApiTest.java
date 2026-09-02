package com.familyfinance.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionTestFixtures;
import com.familyfinance.ledger.FinancialAccountRepository;
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

    @Autowired
    FinancialAccountRepository accountRepository;

    @Autowired
    AppUserRepository appUserRepository;

    @MockitoSpyBean
    CategoryRepository categoryRepository;

    @MockitoSpyBean
    FinancialTransactionRepository transactionRepository;

    @Test
    void concurrentDuplicateCreateReturnsConflictInsteadOfGenericFailure() throws Exception {
        String uniqueName = "并发冲突" + System.nanoTime();
        MockHttpSession firstSession = login();
        MockHttpSession secondSession = login();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseSnapshot> first = executor.submit(
                    () -> createCategoryAtBarrier(ready, start, firstSession, "  " + uniqueName + "  "));
            Future<ResponseSnapshot> second = executor.submit(
                    () -> createCategoryAtBarrier(ready, start, secondSession, uniqueName));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

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
            start.countDown();
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

        realTransactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
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

        realTransactionRepository.save(TransactionTestFixtures.newTransaction(
                accountRepository,
                appUserRepository,
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

    @Test
    void parentConstraintViolationTranslatesToHierarchyValidationInsteadOfDuplicateName() throws Exception {
        MockHttpSession session = login();
        String parentName = "约束父类" + System.nanoTime();
        MvcResult parentResult = mvc.perform(post("/api/categories")
                        .session(session).with(csrf()).contentType("application/json")
                        .content("""
                                {"kind":"expense","name":"%s","color":"#224466","parentId":null}
                                """.formatted(parentName)))
                .andReturn();
        long parentId = Long.parseLong(parentResult.getResponse().getContentAsString()
                .replaceFirst(".*\"id\":(\\d+).*", "$1"));
        String childName = "约束孩子" + System.nanoTime();
        Mockito.doThrow(new DataIntegrityViolationException(
                        "Referential integrity constraint violation FK_CATEGORIES_PARENT_HOUSEHOLD_KIND"))
                .when(categoryRepository)
                .saveAndFlush(Mockito.argThat(category -> childName.equals(category.getName())));

        MvcResult result = mvc.perform(post("/api/categories")
                        .session(session).with(csrf()).contentType("application/json")
                        .content("""
                                {"kind":"expense","name":"%s","color":"#224466","parentId":%d}
                                """.formatted(childName, parentId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(result.getResponse().getContentAsString()).contains("\"parentId\"");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("名称不能重复");
    }

    private ResponseSnapshot createCategoryAtBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            MockHttpSession session,
            String name) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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
