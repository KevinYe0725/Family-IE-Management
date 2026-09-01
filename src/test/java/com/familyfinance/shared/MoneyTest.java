package com.familyfinance.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesTwoDecimalMoneyToIntegerCents() {
        assertThat(Money.parseCents("12.30")).isEqualTo(1230L);
    }

    @Test
    void formatsIntegerCentsAsTwoDecimalMoney() {
        assertThat(Money.formatCents(1230L)).isEqualTo("12.30");
    }

    @Test
    void acceptsMaximumAndRejectsAmountsThatWouldOverflowDuringCentConversion() {
        assertThat(Money.parseCents("999999999.99")).isEqualTo(99_999_999_999L);
        assertThatThrownBy(() -> Money.parseCents("1000000000.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额不能超过 999,999,999.99");
        assertThatThrownBy(() -> Money.parseCents("184467440737095517"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额不能超过 999,999,999.99");
    }

    @Test
    void rejectsInvalidMoneyInputs() {
        assertThatThrownBy(() -> Money.parseCents("0.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额必须大于 0");
        assertThatThrownBy(() -> Money.parseCents("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额必须大于 0");
        assertThatThrownBy(() -> Money.parseCents("1e2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额格式必须是最多两位小数的数字");
        assertThatThrownBy(() -> Money.parseCents("12.345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额格式必须是最多两位小数的数字");
    }
}
