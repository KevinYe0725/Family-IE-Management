package com.familyfinance.plugins.annualstats;

import com.familyfinance.extension.FinancePlugin;
import com.familyfinance.extension.PluginDescriptor;
import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.shared.ApiEnvelope;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Year;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.plugins.annual-stats.enabled", havingValue = "true", matchIfMissing = true)
public class AnnualStatsPlugin implements FinancePlugin {
    private final LedgerReadPort ledger;
    private final Clock clock;

    public AnnualStatsPlugin(LedgerReadPort ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("annual-stats", "1.0.0", 1, "年度统计",
                "查看全年收支与月平均水平", "/workspace/extensions/annual-stats", List.of("ledger.read"));
    }

    @GetMapping("/api/plugins/annual-stats")
    public ApiEnvelope<Report> report(Authentication authentication, @RequestParam(required = false) Integer year) {
        int selectedYear = year == null ? Year.now(clock).getValue() : year;
        var rows = ledger.readYear(authentication, selectedYear);
        BigInteger income = BigInteger.ZERO, expense = BigInteger.ZERO;
        for (var row : rows) {
            income = income.add(row.incomeCents());
            expense = expense.add(row.expenseCents());
        }
        return ApiEnvelope.data(new Report(selectedYear, 12,
                new Summary(money(income), money(expense), money(income.subtract(expense)),
                        average(income), average(expense), average(income.subtract(expense))),
                rows.stream().map(row -> new Month(row.month(), money(row.incomeCents()),
                        money(row.expenseCents()), money(row.incomeCents().subtract(row.expenseCents())))).toList()));
    }

    private static String money(BigInteger cents) { return new BigDecimal(cents, 2).toPlainString(); }
    private static String average(BigInteger cents) {
        return new BigDecimal(cents, 2).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP).toPlainString();
    }
    public record Report(int year, int averageMonthCount, Summary summary, List<Month> months) {}
    public record Summary(String income, String expense, String balance,
            String averageIncome, String averageExpense, String averageBalance) {}
    public record Month(int month, String income, String expense, String balance) {}
}
