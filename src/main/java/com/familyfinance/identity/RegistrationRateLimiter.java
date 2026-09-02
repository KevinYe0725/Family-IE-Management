package com.familyfinance.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class RegistrationRateLimiter {

    private static final double CAPACITY = 5;
    private static final int MAX_BUCKETS = 10_000;
    private static final int MAX_EMAIL_IDENTIFIER_CODE_UNITS = 512;
    private static final int MAX_REMOTE_IP_CODE_UNITS = 64;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    RegistrationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    // Every admitted attempt consumes a token; successful requests are intentionally not refunded.
    boolean tryAcquire(String email, String remoteIp) {
        String normalizedEmail = canonicalIdentifier(email, MAX_EMAIL_IDENTIFIER_CODE_UNITS, true);
        String normalizedRemoteIp = canonicalIdentifier(remoteIp, MAX_REMOTE_IP_CODE_UNITS, false);
        return consume(digest(normalizedEmail + '\u0000' + normalizedRemoteIp));
    }

    private synchronized boolean consume(String key) {
        Instant now = clock.instant();
        Bucket bucket = buckets.get(key);
        Bucket refilled = bucket == null ? new Bucket(CAPACITY, now) : refill(bucket, now);
        if (refilled.tokens() < 1) {
            put(key, refilled);
            return false;
        }
        put(key, new Bucket(refilled.tokens() - 1, now));
        return true;
    }

    private void put(String key, Bucket bucket) {
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
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

    private static String digest(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonicalIdentifier(String value, int maximumCodeUnits, boolean lowercase) {
        if (value == null) {
            return "";
        }
        if (value.length() > maximumCodeUnits) {
            return "<oversized>";
        }
        String canonical = value.trim();
        return lowercase ? canonical.toLowerCase(Locale.ROOT) : canonical;
    }

    private record Bucket(double tokens, Instant refilledAt) {
    }
}
