package com.familyfinance.accounting;

import com.familyfinance.fx.FxJournalRates;
import com.familyfinance.shared.DecimalMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Cash displayed as of today; funding authorization remains with the posting engine. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CashPositionService {
    private final JdbcTemplate jdbc;
    private final LedgerReportingService ledger;
    private final FxJournalRates fx;
    private final Clock clock;

    public CashPositionService(JdbcTemplate jdbc, LedgerReportingService ledger, FxJournalRates fx, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.fx = fx;
        this.clock = clock;
    }

    public CashPositionResponse calculate(long householdId) {
        LocalDate asOf = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        // Use the same effective-date snapshot as NetWorthService, including source corrections.
        var balances = ledger.balanceAmountsAsOf(householdId, asOf);
        var accounts = jdbc.query("""
                select id, currency, opening_confirmed, opening_on
                from financial_accounts where household_id=? order by id
                """, (rs, row) -> new CashAccount(rs.getLong("id"), rs.getString("currency"),
                rs.getBoolean("opening_confirmed") && rs.getObject("opening_on", LocalDate.class) != null), householdId);
        BigDecimal known = BigDecimal.ZERO.setScale(2);
        int uninitialized = 0;
        List<CashPositionResponse.Unconverted> unconverted = new ArrayList<>();
        for (CashAccount account : accounts) {
            if (!account.initialized()) {
                uninitialized++;
                continue;
            }
            BigDecimal nativeAmount = balances.getOrDefault("CASH:" + account.id(), BigDecimal.ZERO.setScale(2));
            // Bank parents, investments, credit limits and future liabilities are not cash accounts.
            BigDecimal converted = account.currency().equals("CNY") ? nativeAmount
                    : fx.valuationConvert(account.currency(), nativeAmount, asOf);
            if (converted == null) {
                unconverted.add(new CashPositionResponse.Unconverted(account.id(), account.currency(),
                        DecimalMoney.format(nativeAmount)));
            } else {
                known = known.add(converted);
            }
        }
        String knownAmount = known.setScale(2).toPlainString();
        return new CashPositionResponse(asOf, "CNY", uninitialized == 0 && unconverted.isEmpty() ? knownAmount : null,
                knownAmount, uninitialized, List.copyOf(unconverted));
    }

    private record CashAccount(long id, String currency, boolean initialized) {}
}
