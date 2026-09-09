package com.familyfinance.transaction;

import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.accounting.LedgerReportingService;
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
    private final LedgerReportingService transactions;
    private final TransactionSummaryService summaries;

    public LedgerReadAdapter(CurrentHousehold household, LedgerReportingService transactions, TransactionSummaryService summaries) {
        this.household = household;
        this.transactions = transactions;
        this.summaries = summaries;
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
        transactions.requireComplete(householdId);
        var summary = summaries.summarize(householdId, new TransactionFilter(null,
                LocalDate.of(year,1,1).toString(), LocalDate.of(year,12,31).toString(), null, null, null, null, null));
        if (summary.unconvertedCount() > 0) throw new com.familyfinance.shared.ResourceConflictException(
                "FX_RATE_MISSING", "原币记录已保留，请补充发生日汇率后查看人民币年度收支。");
        for(var item:summary.daily()) {
            int index=item.date().getMonthValue()-1;
            var target=item.kind()==TransactionKind.INCOME?income:expense;
            target[index]=target[index].add(new java.math.BigDecimal(item.amount()).movePointRight(2).toBigIntegerExact());
        }
        List<MonthlyAmount> result = new ArrayList<>();
        for (int i = 0; i < 12; i++) result.add(new MonthlyAmount(i + 1, income[i], expense[i]));
        return List.copyOf(result);
    }
}
