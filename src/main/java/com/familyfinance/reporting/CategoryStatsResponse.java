package com.familyfinance.reporting;

import java.util.List;

public record CategoryStatsResponse(List<Item> items) {
    public record Item(String name, long value) {}
}