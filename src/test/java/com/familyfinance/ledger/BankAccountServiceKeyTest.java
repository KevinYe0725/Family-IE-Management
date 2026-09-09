package com.familyfinance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BankAccountServiceKeyTest {

    @Test
    void fullLongParentKeysWithSamePrefixProduceDistinctBoundedChildKeys() {
        String prefix = "p".repeat(96);
        String first = BankAccountService.childKey(prefix + "A", "CNY");
        String second = BankAccountService.childKey(prefix + "B", "CNY");

        assertThat(first).hasSizeLessThanOrEqualTo(100).isNotEqualTo(second);
        assertThat(second).hasSizeLessThanOrEqualTo(100);
        assertThat(first).startsWith("bank-child:cny:");
    }
}
