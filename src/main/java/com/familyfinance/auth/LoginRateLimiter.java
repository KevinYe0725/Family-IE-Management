package com.familyfinance.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_BUCKETS = 10_000;
    private static final int MAX_LOGIN_CODE_UNITS = 512;
    private static final int MAX_REMOTE_IP_CODE_UNITS = 64;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean tryAcquire(String identifier, String remoteIp) {
        String key = key(identifier, remoteIp);
        Instant now = clock.instant();
        Bucket bucket = buckets.get(key);
        if (bucket == null || !now.isBefore(bucket.windowStartedAt().plus(WINDOW))) {
            put(key, new Bucket(1, now));
            return true;
        }
        if (bucket.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        put(key, new Bucket(bucket.attempts() + 1, bucket.windowStartedAt()));
        return true;
    }

    public synchronized void reset(String identifier, String remoteIp) {
        buckets.remove(key(identifier, remoteIp));
    }

    private void put(String key, Bucket bucket) {
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            buckets.remove(buckets.keySet().iterator().next());
        }
        buckets.put(key, bucket);
    }

    private static String key(String identifier, String remoteIp) {
        String normalizedIdentifier = boundedIdentifier(identifier, MAX_LOGIN_CODE_UNITS, true);
        String normalizedRemoteIp = boundedIdentifier(remoteIp, MAX_REMOTE_IP_CODE_UNITS, false);
        return digest(normalizedIdentifier + '\u0000' + normalizedRemoteIp);
    }

    private static String boundedIdentifier(String value, int maximumCodeUnits, boolean loginIdentifier) {
        if (value == null) {
            return "";
        }
        if (value.length() > maximumCodeUnits) {
            return "<oversized>";
        }
        return loginIdentifier ? DatabaseUserDetailsService.normalizeLogin(value) : value.trim();
    }

    private static String digest(String rawKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Bucket(int attempts, Instant windowStartedAt) {
    }
}
