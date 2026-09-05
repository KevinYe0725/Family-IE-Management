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
        LedgerReadPort port = (auth, year) -> List.of(
                new LedgerReadPort.MonthlyAmount(1, large, BigInteger.valueOf(100)),
                new LedgerReadPort.MonthlyAmount(2, large, BigInteger.ZERO));
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
