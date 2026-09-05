package com.familyfinance.transaction;

import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.category.TransactionKind;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerReadAdapter implements LedgerReadPort {
    private final CurrentHousehold household;
    private final FinancialTransactionRepository transactions;

    public LedgerReadAdapter(CurrentHousehold household, FinancialTransactionRepository transactions) {
        this.household = household;
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<MonthlyAmount> readYear(Authentication authentication, int year) {
        long householdId = household.id(authentication);
        if (year < 1900 || year > 2100) {
            throw new RequestValidationException(Map.of("year", "年份必须在 1900—2100 之间"));
        }
        BigInteger[] income = new BigInteger[12], expense = new BigInteger[12];
        java.util.Arrays.fill(income, BigInteger.ZERO);
        java.util.Arrays.fill(expense, BigInteger.ZERO);
        int page = 0;
        org.springframework.data.domain.Page<FinancialTransaction> batch;
        do {
            batch = transactions.findAll((root, query, cb) -> cb.and(
                    cb.equal(root.get("household").get("id"), householdId),
                    cb.greaterThanOrEqualTo(root.get("occurredOn"), LocalDate.of(year, 1, 1)),
                    cb.lessThan(root.get("occurredOn"), LocalDate.of(year + 1, 1, 1))),
                    PageRequest.of(page++, 500, Sort.by("id")));
            for (var transaction : batch) {
                int index = transaction.getOccurredOn().getMonthValue() - 1;
                var target = transaction.getKind() == TransactionKind.INCOME ? income : expense;
                target[index] = target[index].add(BigInteger.valueOf(transaction.getAmountCents()));
            }
        } while (batch.hasNext());
        List<MonthlyAmount> result = new ArrayList<>();
        for (int i = 0; i < 12; i++) result.add(new MonthlyAmount(i + 1, income[i], expense[i]));
        return List.copyOf(result);
    }
}
