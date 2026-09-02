package com.familyfinance.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    @Test
    void normalizedEmailAndExactDemoAliasShareTheSameLimitBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        assertThat(limiter.tryAcquire("demo", " 203.0.113.20 ")).isTrue();
        assertThat(limiter.tryAcquire(" DEMO@LOCAL.FAMILY ", "203.0.113.20")).isTrue();
        assertThat(limiter.tryAcquire("demo@local.family", "203.0.113.20")).isTrue();
        assertThat(limiter.tryAcquire("DEMO@LOCAL.FAMILY", "203.0.113.20")).isTrue();
        assertThat(limiter.tryAcquire("demo", "203.0.113.20")).isTrue();
        assertThat(limiter.tryAcquire("demo@local.family", "203.0.113.20")).isFalse();

        clock.advance(Duration.ofMinutes(1).minusMillis(1));
        assertThat(limiter.tryAcquire("demo", "203.0.113.20")).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("demo", "203.0.113.20")).isTrue();
    }

    @Test
    void coordinatedBurstAtomicallyAdmitsOnlyFiveAttempts() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(
                new MutableClock(Instant.parse("2026-09-02T00:00:00Z")));
        int workers = 24;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return limiter.tryAcquire("burst@example.com", "203.0.113.21");
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(5);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void storageIsCappedAndContainsOnlyFixedSizeDigestKeys() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(
                new MutableClock(Instant.parse("2026-09-02T00:00:00Z")));
        limiter.tryAcquire("first@example.com", "203.0.113.22");
        String firstKey = buckets(limiter).keySet().iterator().next().toString();

        for (int index = 0; index < 10_000; index++) {
            limiter.tryAcquire("user-" + index + "@example.com", "203.0.113.22");
        }

        Map<?, ?> buckets = buckets(limiter);
        assertThat(buckets).hasSize(10_000);
        assertThat(buckets.containsKey(firstKey)).isFalse();
        assertThat(buckets.keySet()).allSatisfy(key -> assertThat((String) key)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .doesNotContain("example.com")
                .doesNotContain("203.0.113.22"));
    }

    private static Map<?, ?> buckets(LoginRateLimiter limiter) throws Exception {
        Field buckets = LoginRateLimiter.class.getDeclaredField("buckets");
        buckets.setAccessible(true);
        return (Map<?, ?>) buckets.get(limiter);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
