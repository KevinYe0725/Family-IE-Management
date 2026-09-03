package com.familyfinance.market;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class MarketConfiguration {
    @Bean
    RestClient.Builder marketRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    MarketSleeper marketSleeper() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false);
            }
        };
    }
}
