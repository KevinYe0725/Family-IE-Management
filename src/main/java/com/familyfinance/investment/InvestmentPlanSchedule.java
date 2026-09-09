package com.familyfinance.investment;

import com.familyfinance.accounting.AccountingCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InvestmentPlanSchedule {
    private static final Logger LOG=LoggerFactory.getLogger(InvestmentPlanSchedule.class);
    private final JdbcTemplate jdbc;
    private final InvestmentPlanService service;
    private final AccountingCommandExecutor executor;
    public InvestmentPlanSchedule(JdbcTemplate jdbc,InvestmentPlanService service,AccountingCommandExecutor executor){this.jdbc=jdbc;this.service=service;this.executor=executor;}
    @Scheduled(cron="0 * * * * *",zone="Asia/Shanghai")
    public void generate(){
        for(Long h:jdbc.queryForList("select distinct p.household_id from investment_plans p join households h on h.id=p.household_id where h.status='ACTIVE'",Long.class)) {
            try{executor.execute(()->service.generateHousehold(h));}
            catch(RuntimeException failure){LOG.warn("Investment reminder generation failed for household {}",h,failure);}
        }
    }
}
