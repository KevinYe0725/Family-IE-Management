package com.familyfinance.market;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketSchedule {
    private final QuoteRefreshService refreshService;

    public MarketSchedule(QuoteRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void refreshWeekdayClose() {
        refreshService.refreshScheduledHouseholds();
    }
}
