package com.familyfinance.ledger.recurring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A review fingerprint, not an authorization credential. Excludes the scheduler's next-due cursor. */
final class RecurringRuleSnapshot {
    private RecurringRuleSnapshot() {}
    static String token(RecurringRule rule) {
        String value = Stream.of(rule.getHousehold().getId(), rule.getId(), rule.getKind(), rule.getAmountCents(),
                rule.getAccount().getId(), rule.getAccount().getCurrency(), rule.getMember().getId(),
                rule.getCategory().getId(), rule.getAssignedUser().getId(), rule.getScheduleType(),
                rule.getIntervalValue(), rule.getDayOfMonth(), rule.getDayOfWeek(), rule.getStartOn(),
                rule.getEndOn(), rule.isActive(), rule.isPaused())
                .map(String::valueOf).collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
