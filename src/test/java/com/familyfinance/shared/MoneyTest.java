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
        assertThatThrownBy(() -> Money.parseCents("1000000000.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("金额不能超过 999,999,999.99");
    }
}
