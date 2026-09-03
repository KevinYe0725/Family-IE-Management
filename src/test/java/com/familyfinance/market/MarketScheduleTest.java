package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.investment.InvestmentAccount;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.InvestmentTradeType;
import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

class MarketScheduleTest {

    @Test
    void weekdaysAtShanghaiCloseDelegateToHouseholdRefreshService() throws Exception {
        QuoteRefreshService refreshService = mock(QuoteRefreshService.class);
        MarketSchedule schedule = new MarketSchedule(refreshService);

        schedule.refreshWeekdayClose();

        verify(refreshService).refreshScheduledHouseholds();
        Method method = MarketSchedule.class.getMethod("refreshWeekdayClose");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 30 16 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void scheduledRefreshPersistsOnlyActualHolidayTradeDateAndContinuesPastBadHousehold() {
        InvestmentTradeRepository trades = mock(InvestmentTradeRepository.class);
        SecurityRepository securities = mock(SecurityRepository.class);
        MarketPriceSnapshotRepository snapshots = mock(MarketPriceSnapshotRepository.class);
        ManualPriceOverrideRepository overrides = mock(ManualPriceOverrideRepository.class);
        HouseholdRepository households = mock(HouseholdRepository.class);
        Household broken = mock(Household.class);
        Household working = mock(Household.class);
        when(broken.getId()).thenReturn(1L);
        when(working.getId()).thenReturn(2L);
        when(households.findAll()).thenReturn(List.of(broken, working));
        when(trades.findActiveAccountTradesByHouseholdId(1L)).thenThrow(new IllegalStateException("broken history"));
        Security security = mock(Security.class);
        when(security.getId()).thenReturn(7L);
        when(security.getTsCode()).thenReturn("000001.SZ");
        when(security.isActive()).thenReturn(true);
        InvestmentAccount account = mock(InvestmentAccount.class);
        when(account.getId()).thenReturn(3L);
        InvestmentTrade trade = mock(InvestmentTrade.class);
        when(trade.getAccount()).thenReturn(account);
        when(trade.getSecurity()).thenReturn(security);
        when(trade.getType()).thenReturn(InvestmentTradeType.BUY);
        when(trade.getQuantity()).thenReturn(new BigDecimal("1.0000"));
        when(trades.findActiveAccountTradesByHouseholdId(2L)).thenReturn(List.of(trade));
        when(securities.findAllById(Set.of(7L))).thenReturn(List.of(security));
        when(securities.findAll()).thenReturn(List.of(security));
        AtomicBoolean holidaySnapshotSaved = new AtomicBoolean();
        LocalDate previousTradingDay = LocalDate.of(2026, 9, 1);
        when(snapshots.findBySecurityIdAndTradeDate(7L, previousTradingDay))
                .thenAnswer(invocation -> holidaySnapshotSaved.get() ? Optional.of(mock(MarketPriceSnapshot.class)) : Optional.empty());
        org.mockito.Mockito.doAnswer(invocation -> {
            holidaySnapshotSaved.set(true);
            return invocation.getArgument(0);
        }).when(snapshots).saveAndFlush(org.mockito.ArgumentMatchers.any(MarketPriceSnapshot.class));
        MarketQuoteProvider provider = new MarketQuoteProvider() {
            @Override public List<DailyQuote> fetchDaily(Set<String> symbols) {
                return List.of(new DailyQuote("000001.SZ", previousTradingDay, 1000, 1000, 1000, 1000, 1000, BigDecimal.ZERO));
            }
        };
        QuoteRefreshService service = new QuoteRefreshService(provider, trades, securities, snapshots, overrides,
                mock(CurrentMembership.class), mock(FamilyMutationAuthorization.class),
                Clock.fixed(Instant.parse("2026-09-03T09:00:00Z"), ZoneId.of("UTC")), duration -> { }, households);

        service.refreshScheduledHouseholds();
        service.refreshScheduledHouseholds();

        ArgumentCaptor<MarketPriceSnapshot> saved = ArgumentCaptor.forClass(MarketPriceSnapshot.class);
        verify(snapshots).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTradeDate()).isEqualTo(previousTradingDay);
        assertThat(holidaySnapshotSaved.get()).isTrue();
    }

    @Test
    void noTokenSchedulerModeDoesNotTouchHouseholds() {
        HouseholdRepository households = mock(HouseholdRepository.class);
        MarketQuoteProvider disabled = new MarketQuoteProvider() {
            @Override public List<DailyQuote> fetchDaily(Set<String> symbols) { throw new AssertionError("must not fetch"); }
            @Override public boolean available() { return false; }
        };
        QuoteRefreshService service = new QuoteRefreshService(disabled, mock(InvestmentTradeRepository.class),
                mock(SecurityRepository.class), mock(MarketPriceSnapshotRepository.class), mock(ManualPriceOverrideRepository.class),
                mock(CurrentMembership.class), mock(FamilyMutationAuthorization.class), Clock.systemUTC(), duration -> { }, households);

        service.refreshScheduledHouseholds();

        verifyNoInteractions(households);
    }
}
