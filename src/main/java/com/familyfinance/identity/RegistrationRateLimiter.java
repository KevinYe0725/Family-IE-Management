package com.familyfinance.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class RegistrationRateLimiter {

    private static final double CAPACITY = 5;
    private static final int MAX_BUCKETS = 10_000;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    RegistrationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean allows(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null || refill(bucket, clock.instant()).tokens() >= 1;
    }

    synchronized void recordFailure(String key) {
        Instant now = clock.instant();
        Bucket bucket = buckets.get(key);
        Bucket refilled = bucket == null ? new Bucket(CAPACITY, now) : refill(bucket, now);
        put(key, new Bucket(refilled.tokens() - 1, now));
    }

    synchronized void recordSuccess(String key) {
        buckets.remove(key);
    }

    private void put(String key, Bucket bucket) {
        if (buckets.size() >= MAX_BUCKETS) {
            String oldestKey = buckets.keySet().iterator().next();
            buckets.remove(oldestKey);
        }
        buckets.put(key, bucket);
    }

    private Bucket refill(Bucket bucket, Instant now) {
        long elapsedMillis = Math.max(0, Duration.between(bucket.refilledAt(), now).toMillis());
        double replenished = elapsedMillis * CAPACITY / REFILL_PERIOD.toMillis();
        return new Bucket(Math.min(CAPACITY, bucket.tokens() + replenished), now);
    }

    private record Bucket(double tokens, Instant refilledAt) {
    }
}
