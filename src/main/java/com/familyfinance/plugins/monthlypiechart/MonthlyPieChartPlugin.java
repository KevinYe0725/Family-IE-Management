package com.familyfinance.plugins.monthlypiechart;

import com.familyfinance.extension.FinancePlugin;
import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.extension.PluginDescriptor;
import com.familyfinance.shared.ApiEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/plugins/monthly-pie-chart")
@ConditionalOnProperty(name = "app.plugins.monthly-pie-chart.enabled", havingValue = "true", matchIfMissing = false)
public class MonthlyPieChartPlugin implements FinancePlugin {

    private final LedgerReadPort ledgerReadPort;

    public MonthlyPieChartPlugin(LedgerReadPort ledgerReadPort) {
        this.ledgerReadPort = ledgerReadPort;
    }

    // 1. 实现 FinancePlugin 接口必须的方法：返回插件描述信息
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                "monthly-pie-chart",
                "1.0.0",
                1,
                "月度收支饼图",
                "查看当月收支分类与成员构成",
                "/workspace/extensions/monthly-pie-chart",
                List.of("ledger.read")
        );
    }

    // 2. 定义返回给前端的数据结构
    public record ChartData(
            List<PieItem> incomeByCategory,
            List<PieItem> expenseByCategory,
            List<PieItem> incomeByMember,
            List<PieItem> expenseByMember
    ) {}

    public record PieItem(String name, String amount) {}

    // 3. 提供图表数据的接口
    @GetMapping
    public ApiEnvelope<ChartData> getChartData(Authentication authentication) {
        YearMonth currentMonth = YearMonth.now();
        LedgerReadPort.MonthlyPieChartData data = ledgerReadPort.getMonthlyPieChartData(authentication, currentMonth);

        ChartData chartData = new ChartData(
                convertToPieItem(data.incomeByCategory()),
                convertToPieItem(data.expenseByCategory()),
                convertToPieItem(data.incomeByMember()),
                convertToPieItem(data.expenseByMember())
        );
        return ApiEnvelope.data(chartData);
    }

    private List<PieItem> convertToPieItem(List<LedgerReadPort.PieItem> items) {
        return items.stream()
                .map(i -> new PieItem(i.name(), i.amount().toString()))
                .toList();
    }
}