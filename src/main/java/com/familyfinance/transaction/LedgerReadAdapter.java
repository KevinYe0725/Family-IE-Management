package com.familyfinance.transaction;

import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.category.Category;
import com.familyfinance.household.FamilyMember;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;

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

    @Override
    public MonthlyPieChartData getMonthlyPieChartData(Authentication authentication, YearMonth month) {
        // 1. 获取当前家庭ID
        long householdId = this.household.id(authentication);

        // 2. 确定查询的时间范围（本月第一天 到 下个月第一天）
        Instant start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        // 3. 查询本月所有交易记录
        List<FinancialTransaction> txList = this.transactions.findAll((root, query, cb) -> {
            // 关键修改在这里：使用 fetch 强制加载关联数据
            root.fetch("category");
            root.fetch("member");
    
            return cb.and(
                cb.equal(root.get("household").get("id"), householdId),
                cb.greaterThanOrEqualTo(root.get("occurredOn"), month.atDay(1)),
                cb.lessThan(root.get("occurredOn"), month.plusMonths(1).atDay(1))
            );
        });

        // 4. 初始化聚合变量
        long totalIncome = 0;
        long totalExpense = 0;
        Map<String, Long> incomeByCategoryMap = new HashMap<>();
        Map<String, Long> expenseByCategoryMap = new HashMap<>();
        Map<String, Long> incomeByMemberMap = new HashMap<>();
        Map<String, Long> expenseByMemberMap = new HashMap<>();

        // 5. 遍历交易记录进行聚合
        for (FinancialTransaction tx : txList) {
            long amount = tx.getAmountCents();
            boolean isIncome = tx.getKind() == TransactionKind.INCOME;
            String categoryName = tx.getCategory().getName();
            String memberName = tx.getMember().getName();

            if (isIncome) {
                totalIncome += amount;
                incomeByCategoryMap.merge(categoryName, amount, Long::sum);
                incomeByMemberMap.merge(memberName, amount, Long::sum);
            } else {
                totalExpense += amount;
                expenseByCategoryMap.merge(categoryName, amount, Long::sum);
                expenseByMemberMap.merge(memberName, amount, Long::sum);
            }
        }

        // 6. 将聚合结果转换为前端需要的 DTO 格式
        PieItem totalPieItem = new PieItem("收支总额", BigDecimal.valueOf(totalIncome - totalExpense)); // 或者分别返回收入和支出
        // 为了饼图清晰，我们分别返回收入和支出总额
        PieItem incomeTotal = new PieItem("总收入", centsToYuan(totalIncome));
        PieItem expenseTotal = new PieItem("总支出", centsToYuan(totalExpense));

        List<PieItem> incomeByCategory = incomeByCategoryMap.entrySet().stream()
                .map(e -> new PieItem(e.getKey(), centsToYuan(e.getValue()))).collect(Collectors.toList());
        List<PieItem> expenseByCategory = expenseByCategoryMap.entrySet().stream()
                .map(e -> new PieItem(e.getKey(), centsToYuan(e.getValue()))).collect(Collectors.toList());
        List<PieItem> incomeByMember = incomeByMemberMap.entrySet().stream()
                .map(e -> new PieItem(e.getKey(), centsToYuan(e.getValue()))).collect(Collectors.toList());
        List<PieItem> expenseByMember = expenseByMemberMap.entrySet().stream()
                .map(e -> new PieItem(e.getKey(), centsToYuan(e.getValue()))).collect(Collectors.toList());

        // 7. 返回最终的数据结构
        // 注意：为了饼图显示，我们将总收入和总支出作为两个独立项
        return new MonthlyPieChartData(
            null, // 总收入和总支出分开显示，此项留空或用于其他用途
            incomeByCategory,
            expenseByCategory,
            incomeByMember,
            expenseByMember
        );
    }

    private BigDecimal centsToYuan(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
