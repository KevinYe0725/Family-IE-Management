package com.familyfinance.market;

import java.util.List;

public record MarketRefreshResponse(String state, int refreshed, String error, List<MarketPriceResponse> quotes) { }
