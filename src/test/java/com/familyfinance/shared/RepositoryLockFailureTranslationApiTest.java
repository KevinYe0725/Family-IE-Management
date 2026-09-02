package com.familyfinance.shared;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.household.HouseholdRepository;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import({RepositoryLockFailureTranslationApiTest.LockProbeController.class,
        RepositoryLockFailureTranslationApiTest.LockProbeService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RepositoryLockFailureTranslationApiTest {
    @Autowired MockMvc mvc;
    @Autowired HouseholdRepository households;
    @Autowired TransactionTemplate transactions;

    @Test
    void repositoryLockTimeoutReturnsRetryableStructuredConflict() throws Exception {
        long householdId = households.findAll().get(0).getId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
            households.findLockedById(householdId).orElseThrow();
            locked.countDown();
            await(release);
        }));
        try {
            if (!locked.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("lock holder did not start");
            mvc.perform(post("/api/test/households/{id}/lock", householdId)
                            .session(login()).with(csrf()).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("LOCK_RETRY_REQUIRED"))
                    .andExpect(jsonPath("$.error.message").value("操作繁忙，请重试"));
        } finally {
            release.countDown();
            holder.get();
            executor.shutdownNow();
        }
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("lock release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @RestController
    static class LockProbeController {
        private final LockProbeService service;
        LockProbeController(LockProbeService service) { this.service = service; }

        @PostMapping("/api/test/households/{id}/lock")
        ApiEnvelope<Map<String, Boolean>> lock(@PathVariable long id, Authentication authentication) {
            service.acquire(id);
            return ApiEnvelope.data(Map.of("acquired", true));
        }
    }

    @Service
    static class LockProbeService {
        private final EntityManager entityManager;
        private final HouseholdRepository households;
        LockProbeService(EntityManager entityManager, HouseholdRepository households) {
            this.entityManager = entityManager;
            this.households = households;
        }

        @Transactional
        public void acquire(long id) {
            entityManager.createNativeQuery("SET LOCK_TIMEOUT 100").executeUpdate();
            households.findLockedById(id).orElseThrow();
        }
    }
}
