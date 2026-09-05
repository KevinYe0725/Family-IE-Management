package com.familyfinance.extension;

import java.math.BigInteger;
import java.util.List;
import org.springframework.security.core.Authentication;

/** Core-owned read capability: household identity always comes from the authenticated user. */
public interface LedgerReadPort {
    List<MonthlyAmount> readYear(Authentication authentication, int year);
    record MonthlyAmount(int month, BigInteger incomeCents, BigInteger expenseCents) {}
}
