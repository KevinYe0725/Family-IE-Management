package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipContext;
import com.familyfinance.investment.InvestmentAccount;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.InvestmentTradeType;
import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class QuoteRefreshServiceTest {
    @Test
    void retriesOnlyTransientProviderFailuresThenDeduplicatesTheHeldSymbol() throws Exception {
        InvestmentTradeRepository trades = mock(InvestmentTradeRepository.class);
        SecurityRepository securities = mock(SecurityRepository.class);
        MarketPriceSnapshotRepository snapshots = mock(MarketPriceSnapshotRepository.class);
        ManualPriceOverrideRepository overrides = mock(ManualPriceOverrideRepository.class);
        CurrentMembership membership = mock(CurrentMembership.class);
        FamilyMutationAuthorization authorization = mock(FamilyMutationAuthorization.class);
        Authentication authentication = mock(Authentication.class);
        var access = mock(FamilyMutationAuthorization.LockedFamilyAccess.class);
        when(authorization.requireAdmin(authentication)).thenReturn(access);
        when(access.context()).thenReturn(new MembershipContext(1L, 1L, HouseholdRole.OWNER));
        Security security = security(7L, "000001.SZ", "平安银行");
        InvestmentAccount account = mock(InvestmentAccount.class);
        when(account.getId()).thenReturn(3L);
        InvestmentTrade buyOne = trade(account, security, "1.0000");
        InvestmentTrade buyTwo = trade(account, security, "2.0000");
        when(trades.findActiveAccountTradesByHouseholdId(1L)).thenReturn(List.of(buyOne, buyTwo));
        when(securities.findAllById(any())).thenReturn(List.of(security));
        when(snapshots.findBySecurityIdInAndTradeDate(any(), any())).thenReturn(List.of());
        when(snapshots.findBySecurityIdAndTradeDate(7L, LocalDate.of(2026, 9, 3))).thenReturn(Optional.empty());
        when(securities.findAll()).thenReturn(List.of(security));
        when(snapshots.findFirstBySecurityIdOrderByTradeDateDescFetchedAtDescIdDesc(7L)).thenReturn(Optional.empty());
        when(overrides.findFirstByHouseholdIdAndSecurityIdAndEffectiveOnLessThanEqualOrderByEffectiveOnDescIdDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        MarketQuoteProvider provider = new MarketQuoteProvider() {
            @Override public List<DailyQuote> fetchDaily(Set<String> symbols) {
                assertThat(symbols).containsExactly("000001.SZ");
                if (calls.incrementAndGet() < 3) throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", true);
                return List.of(new DailyQuote("000001.SZ", LocalDate.of(2026, 9, 3), 1000, 1100, 900, 1050, 1000, BigDecimal.ONE));
            }
        };
        QuoteRefreshService service = new QuoteRefreshService(provider, trades, securities, snapshots, overrides,
                membership, authorization, Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneId.of("UTC")), duration -> { });

        MarketRefreshResponse result = service.refresh(authentication);
        assertThat(calls).hasValue(3);
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.refreshed()).isEqualTo(1);
    }

    private static InvestmentTrade trade(InvestmentAccount account, Security security, String quantity) {
        InvestmentTrade trade = mock(InvestmentTrade.class);
        when(trade.getAccount()).thenReturn(account);
        when(trade.getSecurity()).thenReturn(security);
        when(trade.getType()).thenReturn(InvestmentTradeType.BUY);
        when(trade.getQuantity()).thenReturn(new BigDecimal(quantity));
        return trade;
    }
    private static Security security(long id, String code, String name) {
        Security security = mock(Security.class);
        when(security.getId()).thenReturn(id);
        when(security.getTsCode()).thenReturn(code);
        when(security.getName()).thenReturn(name);
        when(security.isActive()).thenReturn(true);
        return security;
    }
}
