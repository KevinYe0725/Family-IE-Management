package com.familyfinance.market;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MarketController {
    private final QuoteRefreshService refreshes;
    private final ManualQuoteService manual;
    public MarketController(QuoteRefreshService refreshes, ManualQuoteService manual) {
        this.refreshes = refreshes; this.manual = manual;
    }
    @GetMapping("/market-quotes")
    ApiEnvelope<List<MarketPriceResponse>> list(Authentication authentication) {
        return ApiEnvelope.data(refreshes.list(authentication));
    }
    @PostMapping("/market-quotes/refresh")
    ApiEnvelope<MarketRefreshResponse> refresh(Authentication authentication) {
        return ApiEnvelope.data(refreshes.refresh(authentication));
    }
    @PostMapping("/securities/{id}/manual-price")
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<MarketPriceResponse> manual(Authentication authentication, @PathVariable long id,
            @RequestBody ManualPriceRequest request) {
        return ApiEnvelope.data(manual.set(authentication, id, request));
    }
}
