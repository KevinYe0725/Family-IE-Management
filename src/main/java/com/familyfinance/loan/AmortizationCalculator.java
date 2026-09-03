package com.familyfinance.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure, cents-based schedule generator. The final row receives every principal rounding remainder. */
public final class AmortizationCalculator {
    static final int RATE_SCALE = 6;
    static final int WORK_SCALE = 24;
    static final BigDecimal MAX_ANNUAL_RATE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    public List<InstallmentDraft> calculate(
            long principalCents, BigDecimal annualRate, int termMonths, LocalDate startOn, RepaymentMethod method) {
        validate(principalCents, annualRate, termMonths, startOn, method);
        BigDecimal monthlyRate = annualRate.setScale(RATE_SCALE, RoundingMode.UNNECESSARY).divide(TWELVE, WORK_SCALE, RoundingMode.HALF_UP);
        BigDecimal equalPayment = method == RepaymentMethod.EQUAL_PAYMENT
                ? equalPayment(principalCents, monthlyRate, termMonths) : null;
        List<InstallmentDraft> drafts = new ArrayList<>(termMonths);
        long remaining = principalCents;
        for (int index = 1; index <= termMonths; index++) {
            long interest = cents(BigDecimal.valueOf(remaining).multiply(monthlyRate));
            long principal = index == termMonths ? remaining : principalFor(method, principalCents, termMonths, equalPayment, interest, remaining);
            if (principal <= 0 || principal > remaining) principal = remaining;
            remaining = Math.subtractExact(remaining, principal);
            drafts.add(new InstallmentDraft(index, startOn.plusMonths(index), principal, interest, remaining));
        }
        return List.copyOf(drafts);
    }

    private static long principalFor(RepaymentMethod method, long original, int months, BigDecimal payment, long interest, long remaining) {
        if (method == RepaymentMethod.EQUAL_PRINCIPAL) return original / months;
        return Math.subtractExact(cents(payment), interest);
    }

    private static BigDecimal equalPayment(long principalCents, BigDecimal monthlyRate, int months) {
        BigDecimal principal = BigDecimal.valueOf(principalCents);
        if (monthlyRate.signum() == 0) return principal.divide(BigDecimal.valueOf(months), WORK_SCALE, RoundingMode.HALF_UP);
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months);
        return principal.multiply(monthlyRate).multiply(growth)
                .divide(growth.subtract(BigDecimal.ONE), WORK_SCALE, RoundingMode.HALF_UP);
    }

    private static long cents(BigDecimal cents) {
        return cents.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void validate(long principalCents, BigDecimal annualRate, int termMonths, LocalDate startOn, RepaymentMethod method) {
        if (principalCents <= 0 || principalCents > 99_999_999_999L) throw new IllegalArgumentException("principal cents must be 1..99999999999");
        Objects.requireNonNull(annualRate, "annualRate must not be null");
        if (annualRate.scale() > RATE_SCALE || annualRate.signum() < 0 || annualRate.compareTo(MAX_ANNUAL_RATE) > 0) throw new IllegalArgumentException("annual rate must be 0..1 with at most 6 decimals");
        if (termMonths < 1 || termMonths > 360) throw new IllegalArgumentException("term months must be 1..360");
        Objects.requireNonNull(startOn, "startOn must not be null");
        if (method == null || method == RepaymentMethod.CUSTOM) throw new IllegalArgumentException("custom schedules are validated by the loan service");
    }
}
