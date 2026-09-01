package com.familyfinance.shared;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Money {

    private static final BigInteger MAX_CENTS = BigInteger.valueOf(99_999_999_999L);
    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100L);
    private static final Pattern DECIMAL_MONEY = Pattern.compile("^(\\d+)(?:\\.(\\d{1,2}))?$");

    private Money() {
    }

    public static long parseCents(String value) {
        String normalized = value == null ? "" : value.trim();
        Matcher matcher = DECIMAL_MONEY.matcher(normalized);
        if (!matcher.matches()) {
            if (normalized.startsWith("-")) {
                throw new IllegalArgumentException("金额必须大于 0");
            }
            throw new IllegalArgumentException("金额格式必须是最多两位小数的数字");
        }

        BigInteger yuan = new BigInteger(matcher.group(1));
        String fraction = matcher.group(2);
        BigInteger cents = yuan.multiply(ONE_HUNDRED);
        if (fraction != null) {
            long fractionCents = fraction.length() == 1
                    ? Long.parseLong(fraction) * 10
                    : Long.parseLong(fraction);
            cents = cents.add(BigInteger.valueOf(fractionCents));
        }
        if (cents.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        if (cents.compareTo(MAX_CENTS) > 0) {
            throw new IllegalArgumentException("金额不能超过 999,999,999.99");
        }
        return cents.longValueExact();
    }

    public static String formatCents(long cents) {
        return "%d.%02d".formatted(cents / 100, Math.abs(cents % 100));
    }
}
