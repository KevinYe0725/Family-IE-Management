package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LoanPrepaymentPersistenceMappingTest {

    @Test
    void usesIdentityGenerationSupportedByMySql() throws NoSuchFieldException {
        Field id = LoanPrepayment.class.getDeclaredField("id");

        assertThat(id.getAnnotation(GeneratedValue.class).strategy())
                .isEqualTo(GenerationType.IDENTITY);
    }
}
