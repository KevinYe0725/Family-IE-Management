package com.familyfinance.reporting;

import java.util.List;

public record AnalysisResponse(String historyStatus, List<InsightResponse> insights) {
}
