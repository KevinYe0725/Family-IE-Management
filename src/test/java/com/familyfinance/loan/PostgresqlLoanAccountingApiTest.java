package com.familyfinance.loan;

import com.familyfinance.migration.PostgresqlTestDatabase;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

@EnabledIfEnvironmentVariable(named="FAMILY_PG_TEST_URL", matches=".+")
@ContextConfiguration(initializers=PostgresqlTestDatabase.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresqlLoanAccountingApiTest extends LoanAccountingApiTest {}
