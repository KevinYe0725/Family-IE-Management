package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.familyfinance.investment.InvestmentAccount;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.InvestmentTradeType;
import com.familyfinance.investment.Security;
import com.familyfinance.market.MarketPriceResponse;
import com.familyfinance.market.QuoteRefreshService;
import com.familyfinance.market.QuoteSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

    @Test
    void handCalculatedPositionIncludesCostReturnAllocationAndManualFreshness() {
        InvestmentTradeRepository trades = mock(InvestmentTradeRepository.class);
        QuoteRefreshService prices = mock(QuoteRefreshService.class);
        InvestmentAccount account = account(3L, "证券账户");
        Security security = security(7L, "000001.SZ", "平安银行");
        List<InvestmentTrade> history = List.of(
                trade(1L, account, security, InvestmentTradeType.BUY, "100.0000", 1000L, 100L, "2026-09-01"),
                trade(2L, account, security, InvestmentTradeType.BUY, "50.0000", 1200L, 50L, "2026-09-02"),
                trade(3L, account, security, InvestmentTradeType.SELL, "60.0000", 1500L, 80L, "2026-09-03"));
        when(trades.findActiveAccountTradesByHouseholdId(1L)).thenReturn(history);
        when(prices.effectivePrices(1L)).thenReturn(List.of(new MarketPriceResponse(
                7L, "000001.SZ", "平安银行", "16.00", QuoteSource.MANUAL,
                LocalDate.of(2026, 9, 2), null, true, null)));

        PortfolioResponse response = new PortfolioService(trades, prices).portfolio(1L);

        PortfolioPositionResponse position = response.positions().get(0);
        assertThat(position.quantity()).isEqualByComparingTo("90.0000");
        assertThat(position.cost()).isEqualTo("960.90");
        assertThat(position.averageCost()).isEqualTo("10.6767");
        assertThat(position.price()).isEqualTo("16.00");
        assertThat(position.marketValue()).isEqualTo("1440.00");
        assertThat(position.realizedProfit()).isEqualTo("258.60");
        assertThat(position.unrealizedProfit()).isEqualTo("479.10");
        assertThat(position.totalProfit()).isEqualTo("737.70");
        assertThat(position.allocationPercent()).isEqualTo("100.0");
        assertThat(position.source()).isEqualTo(QuoteSource.MANUAL);
        assertThat(position.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(position.stale()).isTrue();
        assertThat(response.totals().cost()).isEqualTo("960.90");
        assertThat(response.totals().marketValue()).isEqualTo("1440.00");
    }

    @Test
    void staleNoPriceAndEmptyPortfolioRemainExplicitRatherThanFabricatingValue() {
        InvestmentTradeRepository trades = mock(InvestmentTradeRepository.class);
        QuoteRefreshService prices = mock(QuoteRefreshService.class);
        InvestmentAccount account = account(3L, "证券账户");
        Security security = security(7L, "000001.SZ", "平安银行");
        List<InvestmentTrade> history = List.of(
                trade(1L, account, security, InvestmentTradeType.BUY, "1.0000", 1000L, 0L, "2026-09-01"));
        when(trades.findActiveAccountTradesByHouseholdId(1L)).thenReturn(history);
        when(prices.effectivePrices(1L)).thenReturn(List.of(new MarketPriceResponse(
                7L, "000001.SZ", "平安银行", null, null, null, null, true, "NO_QUOTE")));

        PortfolioResponse unavailable = new PortfolioService(trades, prices).portfolio(1L);

        assertThat(unavailable.positions()).hasSize(1);
        assertThat(unavailable.positions().get(0).marketValue()).isNull();
        assertThat(unavailable.positions().get(0).unrealizedProfit()).isNull();
        assertThat(unavailable.positions().get(0).totalProfit()).isNull();
        assertThat(unavailable.positions().get(0).stale()).isTrue();
        assertThat(unavailable.positions().get(0).error()).isEqualTo("NO_QUOTE");
        assertThat(unavailable.totals().marketValue()).isNull();

        when(trades.findActiveAccountTradesByHouseholdId(2L)).thenReturn(List.of());
        when(prices.effectivePrices(2L)).thenReturn(List.of());
        PortfolioResponse empty = new PortfolioService(trades, prices).portfolio(2L);
        assertThat(empty.positions()).isEmpty();
        assertThat(empty.totals().cost()).isEqualTo("0.00");
        assertThat(empty.totals().marketValue()).isEqualTo("0.00");
    }

    private static InvestmentTrade trade(long id, InvestmentAccount account, Security security,
            InvestmentTradeType type, String quantity, long price, long fee, String date) {
        InvestmentTrade trade = mock(InvestmentTrade.class);
        when(trade.getId()).thenReturn(id);
        when(trade.getAccount()).thenReturn(account);
        when(trade.getSecurity()).thenReturn(security);
        when(trade.getType()).thenReturn(type);
        when(trade.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(trade.getPriceCents()).thenReturn(price);
        when(trade.getFeeCents()).thenReturn(fee);
        when(trade.getTradedOn()).thenReturn(LocalDate.parse(date));
        return trade;
    }

    private static InvestmentAccount account(long id, String name) {
        InvestmentAccount account = mock(InvestmentAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getName()).thenReturn(name);
        when(account.getBrokerName()).thenReturn("券商");
        return account;
    }

    private static Security security(long id, String code, String name) {
        Security security = mock(Security.class);
        when(security.getId()).thenReturn(id);
        when(security.getTsCode()).thenReturn(code);
        when(security.getName()).thenReturn(name);
        return security;
    }
}
