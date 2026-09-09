package com.familyfinance.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.familyfinance.investment.SecurityService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class OverseasPrecisionTest {
    private final MarketDataClient client = mock(MarketDataClient.class);
    private final SecurityService security = mock(SecurityService.class);
    private final Authentication auth = mock(Authentication.class);
    private final Instant now = Instant.parse("2026-09-08T12:00:00Z");
    private final OverseasMarketService service = new OverseasMarketService(security, client, Clock.fixed(now, ZoneOffset.UTC));

    private OverseasCandleResponse response(String market, String close, String high, String low, String open) {
        boolean hk = market.equals("HK");
        String symbol = hk ? "00700" : "AAPL";
        String zone = hk ? "Asia/Hong_Kong" : "America/New_York";
        var instrument = new OverseasInstrument(symbol, symbol, market, hk ? "HKD" : "USD", hk ? "HKEX" : "NASDAQ", zone);
        var day = LocalDate.of(2026, 6, 17);
        var bar = new CandleBar(day.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(), new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100, null);
        return new OverseasCandleResponse(instrument, symbol, "SINA", "none", day, now, false, true, List.of(bar));
    }

    @Test void preservesTinyHkCloseTailWithoutRoundingAnyPrice() {
        var input = response("HK", "477.20001", "477.2", "469.4", "475");
        when(client.overseasCandles("HK", "00700")).thenReturn(input);
        assertThat(service.candles(auth, "HK", "00700").bars()).isEqualTo(input.bars());
    }

    @Test void rejectsMeaningfulAndPennyErrorsAndDoesNotRelaxOtherBoundsOrUsData() {
        for (var values : List.of(
                new String[]{"HK", "477.21", "477.2", "469.4", "475"},
                new String[]{"HK", "0.01999", "0.022", "0.02", "0.021"},
                new String[]{"HK", "477.2", "477.2", "477.20001", "477.2"},
                new String[]{"HK", "477.2", "477.2", "469.4", "477.20001"},
                new String[]{"US", "477.20001", "477.2", "469.4", "475"})) {
            var input = response(values[0], values[1], values[2], values[3], values[4]);
            when(client.overseasCandles(values[0], input.symbol())).thenReturn(input);
            assertThatThrownBy(() -> service.candles(auth, values[0], input.symbol())).isInstanceOf(MarketProviderException.class);
        }
    }

    @Test void clockSkewAcrossPublicationCutoffCannotAdmitNotYetCompletedTradingDay() {
        Instant actualNow = Instant.parse("2026-09-08T21:28:00Z");
        var clockService = new OverseasMarketService(security, client, Clock.fixed(actualNow, ZoneOffset.UTC));
        var input = response("US", "11", "12", "9", "10");
        var day = LocalDate.of(2026, 9, 8);
        var bar = new CandleBar(day.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli(), new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("9"), new BigDecimal("11"), 100, null);
        when(client.overseasCandles("US", "AAPL")).thenReturn(new OverseasCandleResponse(input.instrument(), "AAPL", "SINA", "none", day, actualNow.plusSeconds(300), false, true, List.of(bar)));
        assertThatThrownBy(() -> clockService.candles(auth, "US", "AAPL")).isInstanceOf(MarketProviderException.class);
    }
}
