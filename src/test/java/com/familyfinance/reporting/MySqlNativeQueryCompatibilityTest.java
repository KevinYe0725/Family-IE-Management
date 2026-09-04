package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class MySqlNativeQueryCompatibilityTest {

    @Test
    void accountBalanceQueryUsesATypeThatMySqlAccepts() throws Exception {
        String sql = queryValue(FinancialAccountRepository.class,
                "findActiveBalanceRowsByHouseholdIdAndOccurredOnBefore", Long.class, LocalDate.class);

        assertThat(sql).containsIgnoringCase("decimal(38,0)");
        assertThat(sql).containsIgnoringCase("concat(");
        assertThat(sql).doesNotContainIgnoringCase("numeric(38)");
        assertThat(sql).doesNotContainIgnoringCase("as char");
        assertThat(sql).doesNotContainIgnoringCase("as varchar");
    }

    @Test
    void budgetExpenseQueryUsesATypeThatMySqlAccepts() throws Exception {
        String sql = queryValue(FinancialTransactionRepository.class,
                "sumBudgetExpenseCents", Long.class, LocalDate.class, LocalDate.class,
                String.class, Long.class, Long.class, boolean.class);

        assertThat(sql).containsIgnoringCase("decimal(38,0)");
        assertThat(sql).containsIgnoringCase("concat(");
        assertThat(sql).doesNotContainIgnoringCase("numeric(38)");
        assertThat(sql).doesNotContainIgnoringCase("as char");
        assertThat(sql).doesNotContainIgnoringCase("as varchar");
    }

    private static String queryValue(Class<?> repository, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = repository.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Query.class).value();
    }
}
