package com.familyfinance.extension;

import java.math.BigInteger;
import java.util.List;
import org.springframework.security.core.Authentication;
import java.math.BigDecimal;
import java.time.YearMonth;

/** Core-owned read capability: household identity always comes from the authenticated user. */
public interface LedgerReadPort {
    List<MonthlyAmount> readYear(Authentication authentication, int year);
    record MonthlyAmount(int month, BigInteger incomeCents, BigInteger expenseCents) {}

        // 1. 定义插件所需的数据结构
    public record MonthlyPieChartData(
        PieItem totalIncomeExpense,
        List<PieItem> incomeByCategory,
        List<PieItem> expenseByCategory,
        List<PieItem> incomeByMember,
        List<PieItem> expenseByMember
    ) {}

    public record PieItem(String name, BigDecimal amount) {}

    // 2. 定义获取数据的方法
    public MonthlyPieChartData getMonthlyPieChartData(Authentication authentication, YearMonth month);
}
