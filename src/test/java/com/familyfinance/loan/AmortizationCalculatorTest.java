package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmortizationCalculatorTest {

    private final AmortizationCalculator calculator = new AmortizationCalculator();

    @Test
    void equalPaymentUsesCentsAndMovesTheFinalRoundingRemainderToTheLastRow() {
        List<InstallmentDraft> schedule = calculator.calculate(
                10_000L, new BigDecimal("0.120000"), 3, LocalDate.of(2024, 1, 31), RepaymentMethod.EQUAL_PAYMENT);

        assertThat(schedule).extracting(InstallmentDraft::dueOn)
                .containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 30));
        assertThat(schedule).extracting(InstallmentDraft::principalCents).containsExactly(3_300L, 3_333L, 3_367L);
        assertThat(schedule).extracting(InstallmentDraft::interestCents).containsExactly(100L, 67L, 34L);
        assertThat(schedule).extracting(InstallmentDraft::remainingPrincipalCents).containsExactly(6_700L, 3_367L, 0L);
        assertThat(schedule.stream().mapToLong(InstallmentDraft::principalCents).sum()).isEqualTo(10_000L);
    }

    @Test
    void equalPrincipalHandlesLeapMonthAndZeroInterestExactly() {
        List<InstallmentDraft> schedule = calculator.calculate(
                100L, BigDecimal.ZERO, 3, LocalDate.of(2024, 1, 31), RepaymentMethod.EQUAL_PRINCIPAL);
        assertThat(schedule).extracting(InstallmentDraft::principalCents).containsExactly(33L, 33L, 34L);
        assertThat(schedule).extracting(InstallmentDraft::interestCents).containsOnly(0L);
        assertThat(schedule).extracting(InstallmentDraft::dueOn)
                .containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 31), LocalDate.of(2024, 4, 30));
    }

    @Test
    void supportsTermBoundariesAndRejectsInvalidOrOverflowingInputs() {
        assertThat(calculator.calculate(1L, BigDecimal.ZERO, 1, LocalDate.of(2025, 2, 28), RepaymentMethod.EQUAL_PAYMENT))
                .singleElement().satisfies(row -> assertThat(row.remainingPrincipalCents()).isZero());
        assertThat(calculator.calculate(36_000L, new BigDecimal("0.000001"), 360, LocalDate.of(2025, 1, 31), RepaymentMethod.EQUAL_PRINCIPAL))
                .hasSize(360);
        assertThatThrownBy(() -> calculator.calculate(0L, BigDecimal.ZERO, 1, LocalDate.now(), RepaymentMethod.EQUAL_PAYMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(100L, new BigDecimal("1.000001"), 1, LocalDate.now(), RepaymentMethod.EQUAL_PAYMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(Long.MAX_VALUE, new BigDecimal("1.000000"), 1, LocalDate.now(), RepaymentMethod.EQUAL_PAYMENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(100L, BigDecimal.ZERO, 1, LocalDate.now(), RepaymentMethod.CUSTOM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
