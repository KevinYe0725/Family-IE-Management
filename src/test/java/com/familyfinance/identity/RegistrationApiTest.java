package com.familyfinance.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.ledger.DefaultFinancialAccountFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(RegistrationApiTest.ClockConfiguration.class)
class RegistrationApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppUserRepository users;

    @Autowired
    HouseholdRepository households;

    @Autowired
    HouseholdMembershipRepository memberships;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    CategoryRepository categories;

    @Autowired
    FinancialAccountRepository accounts;

    @Autowired
    MutableClock clock;

    @Autowired
    RegistrationService registrationService;

    @Autowired
    RegistrationRateLimiter rateLimiter;

    @MockitoSpyBean
    RegistrationDefaults defaults;

    @MockitoSpyBean
    DefaultFinancialAccountFactory defaultAccounts;

    @Test
    void createModeBuildsUserHouseholdOwnerLinkedMemberAndDefaultCategories() throws Exception {
        mvc.perform(register(" Parent@Example.COM ", "凯文爸爸", "family-pass-2026", "CREATE", "凯文之家"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("parent@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("凯文爸爸"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.householdName").value("凯文之家"));

        var user = users.findByEmail("parent@example.com").orElseThrow();
        var membership = memberships.findByUserIdAndStatus(user.getId(), MembershipStatus.ACTIVE).get(0);
        assertThat(membership.getRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(members.findByHouseholdIdOrderById(membership.getHousehold().getId()))
                .anySatisfy(member -> {
                    assertThat(member.getName()).isEqualTo("凯文爸爸");
                    assertThat(member.getLinkedUser().getId()).isEqualTo(user.getId());
                });
        assertThat(categories.findByHouseholdIdOrderById(membership.getHousehold().getId())).isNotEmpty();
        assertThat(accounts.findFirstByHouseholdIdAndArchivedAtIsNullOrderById(membership.getHousehold().getId()))
                .hasValueSatisfying(account -> {
                    assertThat(account.getName()).isEqualTo("默认账户");
                    assertThat(account.getType()).isEqualTo(com.familyfinance.ledger.AccountType.CASH);
                    assertThat(account.getCurrency()).isEqualTo("CNY");
                    assertThat(account.getOpeningBalanceCents()).isZero();
                });
    }

    @Test
    void defaultDataFailureRollsBackUserHouseholdMembershipMemberAndCategories() throws Exception {
        long userCount = users.count();
        long householdCount = households.count();
        long membershipCount = memberships.count();
        long memberCount = members.count();
        long categoryCount = categories.count();
        long accountCount = accounts.count();
        org.mockito.Mockito.doAnswer(invocation -> {
            categories.saveAndFlush(new Category(
                    invocation.getArgument(0),
                    TransactionKind.EXPENSE,
                    "已写入后失败",
                    "#112233",
                    true,
                    invocation.getArgument(1)));
            throw new IllegalStateException("default data failure");
        }).when(defaults).createFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> registrationService.register(new RegistrationService.CreateRegistration(
                "rollback@example.com", "回滚", "family-pass-2026", "回滚家庭")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(users.count()).isEqualTo(userCount);
        assertThat(households.count()).isEqualTo(householdCount);
        assertThat(memberships.count()).isEqualTo(membershipCount);
        assertThat(members.count()).isEqualTo(memberCount);
        assertThat(categories.count()).isEqualTo(categoryCount);
        assertThat(accounts.count()).isEqualTo(accountCount);
    }

    @Test
    void defaultAccountFailureRollsBackTheEntireCreateRegistration() {
        long userCount = users.count();
        long householdCount = households.count();
        long membershipCount = memberships.count();
        long memberCount = members.count();
        long categoryCount = categories.count();
        long accountCount = accounts.count();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("default account failure");
        }).when(defaultAccounts).createFor(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> registrationService.register(new RegistrationService.CreateRegistration(
                "account-rollback@example.com", "账户回滚", "family-pass-2026", "账户回滚家庭")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(users.count()).isEqualTo(userCount);
        assertThat(households.count()).isEqualTo(householdCount);
        assertThat(memberships.count()).isEqualTo(membershipCount);
        assertThat(members.count()).isEqualTo(memberCount);
        assertThat(categories.count()).isEqualTo(categoryCount);
        assertThat(accounts.count()).isEqualTo(accountCount);
    }

    @Test
    void duplicateEmailUsesGenericConflictWithoutExistenceLeakage() throws Exception {
        mvc.perform(register("duplicate@example.com", "第一次", "family-pass-2026", "CREATE", "第一家庭"))
                .andExpect(status().isCreated());

        mvc.perform(register(" DUPLICATE@example.com ", "第二次", "family-pass-2026", "CREATE", "第二家庭"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REGISTRATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("注册暂时无法完成"));
    }

    @Test
    void registrationValidatesPasswordBoundariesAndHouseholdName() throws Exception {
        mvc.perform(register("seven@example.com", "七", password(7), "CREATE", "七家庭"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.password").exists());
        mvc.perform(register("eight@example.com", "八", password(8), "CREATE", "八家庭"))
                .andExpect(status().isCreated());
        mvc.perform(register("seventy-two@example.com", "七二", password(72), "CREATE", "七二家庭"))
                .andExpect(status().isCreated());
        mvc.perform(register("seventy-three@example.com", "七三", password(73), "CREATE", "七三家庭"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.password").exists());
        mvc.perform(register("missing-household@example.com", "无家庭", "family-pass-2026", "CREATE", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.householdName").exists());
    }

    @Test
    void registrationCountsUnicodeCodePointsAndRejectsPasswordsOverBcryptUtf8Limit() throws Exception {
        mvc.perform(register("emoji-too-short@example.com", "表情", emoji(4), "CREATE", "表情家庭"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.password").exists());
        mvc.perform(register("emoji-valid@example.com", "表情", emoji(8), "CREATE", "表情家庭"))
                .andExpect(status().isCreated());
        mvc.perform(register("chinese-72-bytes@example.com", "中文", "中".repeat(24), "CREATE", "中文家庭"))
                .andExpect(status().isCreated());
        mvc.perform(register("chinese-75-bytes@example.com", "中文", "中".repeat(25), "CREATE", "中文家庭"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.password").exists());
    }

    @Test
    void registrationKeepsSupplementaryNamesWithinH2VarcharCapacity() throws Exception {
        String acceptedDisplayName = emoji(20);
        String acceptedHouseholdName = emoji(127);
        mvc.perform(register("emoji-names-accepted@example.com", acceptedDisplayName, "family-pass-2026", "CREATE", acceptedHouseholdName))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayName").value(acceptedDisplayName))
                .andExpect(jsonPath("$.data.householdName").value(acceptedHouseholdName));
        var user = users.findByEmail("emoji-names-accepted@example.com").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo(acceptedDisplayName);
        assertThat(households.findById(user.getHousehold().getId()).orElseThrow().getName())
                .isEqualTo(acceptedHouseholdName);

        mvc.perform(register("emoji-display-rejected@example.com", emoji(21), "family-pass-2026", "CREATE", "普通家庭"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.displayName").exists());
        mvc.perform(register("emoji-household-rejected@example.com", "普通用户", "family-pass-2026", "CREATE", emoji(128)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.householdName").exists());
    }

    @Test
    void registrationRejectsDeclaredOversizedBodiesBeforeTheyReachLimiterState() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"大请求","password":"family-pass-2026","mode":"CREATE","householdName":"大请求家庭"}
                                """.formatted("a".repeat(5_000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fields.request").exists());
    }

    @Test
    void joinModeIsNotImplementedInThisTask() throws Exception {
        mvc.perform(register("join-later@example.com", "稍后加入", "family-pass-2026", "JOIN", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registrationStillRequiresCsrfWithStructuredForbiddenError() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"csrf@example.com","displayName":"CSRF","password":"family-pass-2026","mode":"CREATE","householdName":"CSRF 家庭"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void registrationRateLimitUsesNormalizedEmailAndRemoteIpWithoutLeakingExistence() throws Exception {
        mvc.perform(register("limited@example.com", "限流", "family-pass-2026", "CREATE", "限流家庭", "203.0.113.10"))
                .andExpect(status().isCreated());
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(register(" LIMITED@example.com ", "限流", "family-pass-2026", "CREATE", "限流家庭", "203.0.113.10"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.message").value("注册暂时无法完成"));
        }
        mvc.perform(register("limited@example.com", "限流", "family-pass-2026", "CREATE", "限流家庭", "203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("REGISTRATION_RATE_LIMITED"))
                .andExpect(jsonPath("$.error.message").value("注册暂时无法完成"));

        mvc.perform(register("unaffected@example.com", "正常", "family-pass-2026", "CREATE", "正常家庭", "203.0.113.10"))
                .andExpect(status().isCreated());

        clock.advance(Duration.ofMinutes(1));
        mvc.perform(register("limited@example.com", "限流", "family-pass-2026", "CREATE", "限流家庭", "203.0.113.10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("注册暂时无法完成"));
    }

    @Test
    void rateLimitAdmissionIsAtomicUnderAConcurrentBurst() throws Exception {
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return rateLimiter.tryAcquire("burst@example.com", "203.0.113.11");
                    }))
                    .toList();
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rateLimiterStoresOnlyFixedSizeDigestKeysForOversizedInputs() throws Exception {
        rateLimiter.tryAcquire("very-long-email-" + "x".repeat(20_000), "203.0.113.12");

        java.lang.reflect.Field bucketsField = RegistrationRateLimiter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        Map<?, ?> buckets = (Map<?, ?>) bucketsField.get(rateLimiter);
        assertThat(buckets.keySet()).allSatisfy(key -> assertThat((String) key)
                .hasSize(64)
                .matches("[0-9a-f]{64}"));
    }

    @Test
    void changePasswordRequiresCurrentPasswordRejectsReuseAndKeepsSessionValid() throws Exception {
        mvc.perform(register("password@example.com", "改密码", "family-pass-2026", "CREATE", "密码家庭"))
                .andExpect(status().isCreated());
        MockHttpSession session = login("password@example.com", "family-pass-2026");

        mvc.perform(changePassword(session, "wrong-password", "new-family-pass-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_FAILED"));
        mvc.perform(changePassword(session, "family-pass-2026", "family-pass-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_FAILED"));
        mvc.perform(changePassword(session, "family-pass-2026", password(7)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.newPassword").exists());
        mvc.perform(changePassword(session, "family-pass-2026", password(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.newPassword").exists());
        mvc.perform(changePassword(session, "family-pass-2026", emoji(4)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.newPassword").exists());
        mvc.perform(changePassword(session, "family-pass-2026", "中".repeat(25)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.newPassword").exists());

        mvc.perform(changePassword(session, "family-pass-2026", "中".repeat(24)))
                .andExpect(status().isNoContent());
        mvc.perform(changePassword(session, "中".repeat(24), emoji(8)))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("family-pass-2026")
                        .doesNotContain(emoji(8)));
        mvc.perform(get("/api/session").session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        login("password@example.com", emoji(8));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            String email, String displayName, String password, String mode, String householdName) {
        return register(email, displayName, password, mode, householdName, "127.0.0.1");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            String email, String displayName, String password, String mode, String householdName, String remoteIp) {
        String householdJson = householdName == null ? "null" : "\"" + householdName + "\"";
        return post("/api/auth/register")
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(remoteIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","displayName":"%s","password":"%s","mode":"%s","householdName":%s}
                        """.formatted(email, displayName, password, mode, householdJson));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changePassword(
            MockHttpSession session, String currentPassword, String newPassword) {
        return post("/api/auth/change-password")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}
                        """.formatted(currentPassword, newPassword));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", email)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String password(int length) {
        return "a".repeat(length);
    }

    private static String emoji(int count) {
        return "😀".repeat(count);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock registrationClock() {
            return new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
