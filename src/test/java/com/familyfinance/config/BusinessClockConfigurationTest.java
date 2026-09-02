package com.familyfinance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BusinessClockConfigurationTest {

    @Test
    void utcSixteenHundredStartsTheNextShanghaiBusinessDayWithoutChangingTheInstant() {
        Instant shanghaiMidnight = Instant.parse("2026-09-02T16:00:00Z");
        Clock configured = new SecurityConfig().clock();
        Clock fixed = Clock.fixed(shanghaiMidnight, configured.getZone());

        assertThat(fixed.instant()).isEqualTo(shanghaiMidnight);
        assertThat(LocalDate.now(fixed)).isEqualTo(LocalDate.parse("2026-09-03"));
    }
}
