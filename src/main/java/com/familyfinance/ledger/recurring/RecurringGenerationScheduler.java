package com.familyfinance.ledger.recurring;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecurringGenerationScheduler {
    private final RecurringService recurringService;

    public RecurringGenerationScheduler(RecurringService recurringService) {
        this.recurringService = recurringService;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Shanghai")
    public void generateDailyOccurrences() {
        recurringService.generateDueOccurrences();
    }
}
