package com.familyfinance.market;

import java.time.LocalDate;

public record ManualPriceRequest(String price, LocalDate effectiveOn, String note) { }
