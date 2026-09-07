package com.familyfinance.extension;

import com.familyfinance.plugins.annualstats.AnnualStatsPlugin;
import java.math.BigInteger;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnnualStatsCalculationTest {
    @Test void roundsAveragesAndAggregatesBeyondLongWithoutOverflow() {
        var large = BigInteger.valueOf(Long.MAX_VALUE);
        LedgerReadPort port = new LedgerReadPort() {
            @Override
            public List<MonthlyAmount> readYear(org.springframework.security.core.Authentication auth, int year) {
                return List.of(
                        new MonthlyAmount(1, large, BigInteger.valueOf(100)),
                        new MonthlyAmount(2, large, BigInteger.ZERO));
            }

            @Override
            public MonthlyPieChartData getMonthlyPieChartData(
                    org.springframework.security.core.Authentication auth, java.time.YearMonth month) {
                return new MonthlyPieChartData(null, List.of(), List.of(), List.of(), List.of());
            }
        };
        var report = new AnnualStatsPlugin(port, Clock.systemUTC()).report(null, 2026).data();
        assertThat(report.summary().income()).isEqualTo("184467440737095516.14");
        assertThat(report.summary().averageExpense()).isEqualTo("0.08");
        assertThat(report.summary().balance()).isEqualTo("184467440737095515.14");
    }

    @Test void rejectsDuplicatePluginIds() {
        FinancePlugin plugin = () -> new PluginDescriptor("annual-stats", "1.0.0", 1,
                "年度统计", "", "/workspace/extensions/annual-stats", List.of("ledger.read"));
        assertThatThrownBy(() -> new PluginRegistry(List.of(plugin, plugin))).isInstanceOf(IllegalStateException.class);
    }
}
